package com.examprep.api.controller;

import com.examprep.api.dto.request.QueryRequest;
import com.examprep.api.dto.response.QueryResponse;
import com.examprep.data.entity.QueryHistory;
import com.examprep.data.entity.User;
import com.examprep.data.repository.QueryHistoryRepository;
import com.examprep.data.repository.UserRepository;
import com.examprep.llm.service.EmbeddingService;
import com.examprep.llm.service.RagService;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryController {
    
    private final RagService ragService;
    private final QueryHistoryRepository queryHistoryRepository;
    private final UserRepository userRepository;
    private final EmbeddingService embeddingService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @PostMapping
    public ResponseEntity<QueryResponse> query(
            @Valid @RequestBody QueryRequest request,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        long startTime = System.currentTimeMillis();
        
        // Generate query embedding for history
        float[] queryEmbedding = embeddingService.generateEmbedding(request.getQuestion());
        
        RagService.RagResult result = ragService.query(
            userId,
            request.getQuestion(),
            request.getMarks(),
            request.getDocumentIds(),
            request.getFormatInstructions()
        );
        
        long totalTime = System.currentTimeMillis() - startTime;
        
        // Save to query history
        try {
            String sourcesJson = objectMapper.writeValueAsString(result.getSources());
            
            QueryHistory history = QueryHistory.builder()
                .user(user)
                .queryText(request.getQuestion())
                .queryEmbedding(queryEmbedding)
                .marksRequested(request.getMarks())
                .answerText(result.getAnswer())
                .sourcesUsed(sourcesJson)
                .retrievalTimeMs(result.getRetrievalTimeMs().intValue())
                .generationTimeMs(result.getGenerationTimeMs().intValue())
                .totalTimeMs((int) totalTime)
                .chunksRetrieved(result.getChunksUsed())
                .build();
            
            queryHistoryRepository.save(history);
        } catch (Exception e) {
            // Log but don't fail the request
            e.printStackTrace();
        }
        
        QueryResponse response = QueryResponse.builder()
            .answer(result.getAnswer())
            .sources(result.getSources().stream()
                .map(source -> QueryResponse.SourceInfo.builder()
                    .documentId(source.getDocumentId())
                    .fileName(source.getFileName())
                    .pageNumber(source.getPageNumber())
                    .slideNumber(source.getSlideNumber())
                    .excerpt(source.getExcerpt())
                    .build())
                .collect(Collectors.toList()))
            .metadata(QueryResponse.Metadata.builder()
                .retrievalTimeMs(result.getRetrievalTimeMs())
                .generationTimeMs(result.getGenerationTimeMs())
                .totalTimeMs(result.getRetrievalTimeMs() + result.getGenerationTimeMs())
                .chunksUsed(result.getChunksUsed())
                .build())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/history")
    public ResponseEntity<Page<QueryHistoryResponse>> getQueryHistory(
            Pageable pageable,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        
        Page<QueryHistory> history = queryHistoryRepository.findByUser_IdOrderByCreatedAtDesc(userId, pageable);
        
        Page<QueryHistoryResponse> response = history.map(h -> QueryHistoryResponse.builder()
            .id(h.getId())
            .question(h.getQueryText())
            .answer(h.getAnswerText() != null && h.getAnswerText().length() > 200 
                ? h.getAnswerText().substring(0, 200) + "..." 
                : h.getAnswerText())
            .marksRequested(h.getMarksRequested())
            .createdAt(h.getCreatedAt())
            .build());
        
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

