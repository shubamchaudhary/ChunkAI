package com.examprep.llm.service;

import com.examprep.data.entity.DocumentChunk;
import com.examprep.data.repository.DocumentChunkRepository;
import com.examprep.llm.client.GeminiClient;
import com.examprep.llm.prompt.ExamAnswerPrompts;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {
    
    private final EmbeddingService embeddingService;
    private final GeminiClient geminiClient;
    private final DocumentChunkRepository chunkRepository;
    
    /**
     * Main RAG pipeline: Retrieve relevant chunks and generate answer
     */
    public RagResult query(
            UUID userId,
            String question,
            Integer marks,
            List<UUID> documentIds,
            String customFormat
    ) {
        long startTime = System.currentTimeMillis();
        
        // Step 1: Generate embedding for the question
        log.info("Generating embedding for query: {}", question.substring(0, Math.min(50, question.length())));
        float[] queryEmbedding = embeddingService.generateEmbedding(question);
        long embeddingTime = System.currentTimeMillis() - startTime;
        
        // Step 2: Retrieve similar chunks
        log.info("Searching for similar chunks for user: {}", userId);
        String vectorString = embeddingService.toVectorString(queryEmbedding);
        
        UUID[] docIdArray = (documentIds != null && !documentIds.isEmpty()) 
            ? documentIds.toArray(new UUID[0]) 
            : null;
        
        log.debug("Vector string length: {}, Document IDs: {}", 
            vectorString.length(), 
            docIdArray != null ? docIdArray.length : "all");
        
        List<DocumentChunk> relevantChunks = chunkRepository.findSimilarChunksCustom(
            userId,
            vectorString,
            docIdArray,
            10 // Top 10 chunks
        );
        long retrievalTime = System.currentTimeMillis() - startTime - embeddingTime;
        
        log.info("Found {} relevant chunks for query", relevantChunks.size());
        
        if (relevantChunks.isEmpty()) {
            log.warn("No chunks found for user {} with query: {}", userId, question);
            return RagResult.builder()
                .answer("I couldn't find relevant information in your documents to answer this question. Please make sure you've uploaded documents related to this topic.")
                .sources(List.of())
                .retrievalTimeMs(retrievalTime)
                .generationTimeMs(0L)
                .chunksUsed(0)
                .build();
        }
        
        // Step 3: Build context from chunks
        String context = buildContext(relevantChunks);
        
        // Step 4: Build prompt
        String systemPrompt = ExamAnswerPrompts.SYSTEM_PROMPT;
        String userPrompt = ExamAnswerPrompts.buildUserPrompt(question, context, marks, customFormat);
        
        // Step 5: Generate answer
        log.info("Generating answer with {} chunks as context", relevantChunks.size());
        String answer = geminiClient.generateContent(userPrompt, systemPrompt);
        long generationTime = System.currentTimeMillis() - startTime - embeddingTime - retrievalTime;
        
        // Step 6: Build response
        List<SourceInfo> sources = relevantChunks.stream()
            .map(chunk -> SourceInfo.builder()
                .documentId(chunk.getDocument().getId())
                .fileName(chunk.getDocument().getFileName())
                .pageNumber(chunk.getPageNumber())
                .slideNumber(chunk.getSlideNumber())
                .excerpt(truncate(chunk.getContent(), 200))
                .build())
            .collect(Collectors.toList());
        
        return RagResult.builder()
            .answer(answer)
            .sources(sources)
            .retrievalTimeMs(retrievalTime)
            .generationTimeMs(generationTime)
            .chunksUsed(relevantChunks.size())
            .build();
    }
    
    private String buildContext(List<DocumentChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            sb.append(String.format(
                "[Source %d: %s, Page/Slide %d]\n%s\n\n",
                i + 1,
                chunk.getDocument().getFileName(),
                chunk.getSlideNumber() != null ? chunk.getSlideNumber() : chunk.getPageNumber(),
                chunk.getContent()
            ));
        }
        return sb.toString();
    }
    
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
    
    @Data
    @Builder
    public static class RagResult {
        private String answer;
        private List<SourceInfo> sources;
        private Long retrievalTimeMs;
        private Long generationTimeMs;
        private Integer chunksUsed;
    }
    
    @Data
    @Builder
    public static class SourceInfo {
        private UUID documentId;
        private String fileName;
        private Integer pageNumber;
        private Integer slideNumber;
        private String excerpt;
    }
}

