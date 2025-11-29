package com.examprep.api.controller;

import com.examprep.api.dto.response.QueryResponse;
import com.examprep.common.constants.ProcessingStatus;
import lombok.extern.slf4j.Slf4j;
import com.examprep.data.entity.Chat;
import com.examprep.data.entity.Document;
import com.examprep.data.entity.QueryHistory;
import com.examprep.data.entity.User;
import com.examprep.data.repository.ChatRepository;
import com.examprep.data.repository.DocumentRepository;
import com.examprep.core.query.QueryOrchestrator;
import com.examprep.core.query.model.QueryResult;
import com.examprep.data.repository.QueryHistoryRepository;
import com.examprep.data.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.validation.Valid;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {
    
    private final QueryOrchestrator queryOrchestrator;
    private final QueryHistoryRepository queryHistoryRepository;
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    private final DocumentRepository documentRepository;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @PostMapping
    public ResponseEntity<QueryResponse> query(
            @Valid @RequestBody com.examprep.api.dto.request.QueryRequest request,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (request.getChatId() == null) {
            return ResponseEntity.badRequest().build();
        }
        
        Chat chat = chatRepository.findById(request.getChatId())
            .orElseThrow(() -> new RuntimeException("Chat not found"));
        
        // Verify chat belongs to user
        if (!chat.getUser().getId().equals(userId)) {
            return ResponseEntity.status(403).build();
        }
        
        UUID chatId = request.getChatId();
        // Default to false if not explicitly set to true
        boolean useCrossChat = Boolean.TRUE.equals(request.getUseCrossChat());
        
        String question = request.getQuestion();
        log.info("Query request - chatId: {}, useCrossChat: {}, question: {}", 
            chatId, useCrossChat, question != null ? question.substring(0, Math.min(50, question.length())) : "null");
        
        // Check if there are any documents in this chat that are still processing
        // Use findByChatIdExcludingEmbedding to avoid loading vector columns with NULL values
        List<Document> processingDocs = documentRepository.findByChatIdExcludingEmbedding(chatId).stream()
            .filter(doc -> doc.getProcessingStatus() == ProcessingStatus.PENDING 
                || doc.getProcessingStatus() == ProcessingStatus.PROCESSING)
            .toList();
        
        if (!processingDocs.isEmpty()) {
            return ResponseEntity.status(400).body(
                com.examprep.api.dto.response.QueryResponse.builder()
                    .answer("Please wait for all documents to finish processing before asking questions. " +
                        processingDocs.size() + " document(s) are still being processed.")
                    .sources(List.of())
                    .metadata(com.examprep.api.dto.response.QueryResponse.Metadata.builder()
                        .retrievalTimeMs(0L)
                        .generationTimeMs(0L)
                        .totalTimeMs(0L)
                        .chunksUsed(0)
                        .build())
                    .build()
            );
        }
        
        // Allow queries even without documents - system can use internet search
        // Only check for processing documents, not for completed documents
        // This allows users to ask questions without uploading files
        
        // Get conversation history for context
        List<com.examprep.core.query.model.QueryRequest.ChatMessage> chatHistory = new ArrayList<>();
        try {
            List<Object[]> recentHistory = queryHistoryRepository.findHistoryByUserIdAndChatIdNative(
                userId, chatId, 
                org.springframework.data.domain.PageRequest.of(0, 50) // Last 50 Q&A pairs
            );
            // Reverse to get chronological order (oldest first)
            for (int i = recentHistory.size() - 1; i >= 0; i--) {
                Object[] row = recentHistory.get(i);
                String q = (String) row[3]; // query_text
                String a = (String) row[5]; // answer_text
                if (q != null && a != null && !a.trim().isEmpty()) {
                    chatHistory.add(com.examprep.core.query.model.QueryRequest.ChatMessage.builder()
                        .role("user")
                        .content(q)
                        .build());
                    chatHistory.add(com.examprep.core.query.model.QueryRequest.ChatMessage.builder()
                        .role("assistant")
                        .content(a)
                        .build());
                }
            }
            log.info("Loaded {} Q&A pairs for conversation context", chatHistory.size() / 2);
        } catch (Exception e) {
            log.warn("Failed to load conversation history", e);
        }
        
        // Build core query request
        com.examprep.core.query.model.QueryRequest coreRequest = com.examprep.core.query.model.QueryRequest.builder()
            .userId(userId)
            .chatId(chatId)
            .question(request.getQuestion())
            .marks(request.getMarks())
            .formatInstructions(request.getFormatInstructions())
            .documentIds(request.getDocumentIds())
            .useCrossChat(useCrossChat)
            .chatHistory(chatHistory)
            .build();
        
        // Process query using v2.0 QueryOrchestrator
        com.examprep.core.query.model.QueryResult result = queryOrchestrator.processQuery(coreRequest);
        
        // Convert to API response
        com.examprep.api.dto.response.QueryResponse response = com.examprep.api.dto.response.QueryResponse.builder()
            .answer(result.getAnswer())
            .sources(result.getSources() != null ? result.getSources().stream()
                .map(source -> com.examprep.api.dto.response.QueryResponse.SourceInfo.builder()
                    .documentId(source.getDocumentId())
                    .fileName(source.getFileName())
                    .pageNumber(source.getPageNumber())
                    .slideNumber(source.getSlideNumber())
                    .excerpt(source.getExcerpt())
                    .build())
                .collect(Collectors.toList()) : List.of())
            .metadata(com.examprep.api.dto.response.QueryResponse.Metadata.builder()
                .retrievalTimeMs(0L) // Not tracked separately in v2.0
                .generationTimeMs(0L) // Not tracked separately in v2.0
                .totalTimeMs(0L) // Tracked in QueryResult
                .chunksUsed(result.getChunksUsed())
                .build())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/history")
    public ResponseEntity<Page<QueryHistoryResponse>> getQueryHistory(
            @RequestParam(required = false) UUID chatId,
            Pageable pageable,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        
        List<Object[]> results;
        if (chatId != null) {
            results = queryHistoryRepository.findHistoryByUserIdAndChatIdNative(userId, chatId, pageable);
        } else {
            results = queryHistoryRepository.findHistoryByUserIdNative(userId, pageable);
        }
        
        List<QueryHistoryResponse> historyList = results.stream().map(row -> {
            UUID id = (UUID) row[0];
            String queryText = (String) row[3];
            String answerText = (String) row[5];
            Integer marksRequested = row[4] != null ? (Integer) row[4] : null;
            java.time.Instant createdAt = null;
            if (row[11] != null) {
                if (row[11] instanceof java.time.Instant) {
                    createdAt = (java.time.Instant) row[11];
                } else if (row[11] instanceof java.sql.Timestamp) {
                    createdAt = ((java.sql.Timestamp) row[11]).toInstant();
                } else if (row[11] instanceof java.time.OffsetDateTime) {
                    createdAt = ((java.time.OffsetDateTime) row[11]).toInstant();
                }
            }
            
            return QueryHistoryResponse.builder()
                .id(id)
                .question(queryText)
                .answer(answerText) // Return full answer without truncation
                .marksRequested(marksRequested)
                .createdAt(createdAt)
                .build();
        }).toList();
        
        // Convert to Page (simplified - in production, handle pagination properly)
        org.springframework.data.domain.PageImpl<QueryHistoryResponse> response = 
            new org.springframework.data.domain.PageImpl<>(historyList, pageable, historyList.size());
        
        return ResponseEntity.ok(response);
    }
    
    @Data
    @Builder
    private static class QueryHistoryResponse {
        private UUID id;
        private String question;
        private String answer;
        private Integer marksRequested;
        private java.time.Instant createdAt;
    }
}

