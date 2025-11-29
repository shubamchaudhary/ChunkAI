package com.examprep.core.query;

import com.examprep.core.query.model.QueryRequest;
import com.examprep.core.query.model.QueryResult;
import com.examprep.data.repository.DocumentChunkRepositoryCustom;
import com.examprep.llm.client.GeminiClient;
import com.examprep.llm.keymanager.ApiKeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MapReduceOrchestrator {
    
    private final ApiKeyManager keyManager;
    private final GeminiClient geminiClient;
    
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    
    // Configuration
    private static final int MAX_TOKENS_PER_BATCH = 25_000;
    private static final int MAX_PARALLEL_CALLS = 5;
    private static final int MAX_REDUCE_ITERATIONS = 3;
    
    /**
     * Process large context using Map-Reduce pattern.
     */
    public QueryResult process(
            String question,
            List<DocumentChunkRepositoryCustom.ScoredChunk> allChunks,
            List<QueryRequest.ChatMessage> chatHistory) {
        
        log.info("Starting Map-Reduce for {} chunks", allChunks.size());
        int totalLlmCalls = 0;
        
        // ============================================
        // PHASE 1: CREATE SMART BATCHES
        // ============================================
        List<ChunkBatch> batches = createSmartBatches(allChunks, MAX_TOKENS_PER_BATCH);
        log.info("Created {} batches", batches.size());
        
        // ============================================
        // PHASE 2: MAP - Parallel Extraction
        // ============================================
        List<ExtractionResult> extractions = executeMapPhase(batches, question);
        totalLlmCalls += batches.size();
        
        // ============================================
        // PHASE 3: REDUCE - Iterative Combination
        // ============================================
        String combinedKnowledge = executeReducePhase(extractions, question);
        
        // Check if combined result is still too large
        int iteration = 0;
        while (estimateTokens(combinedKnowledge) > MAX_TOKENS_PER_BATCH 
               && iteration < MAX_REDUCE_ITERATIONS) {
            
            log.info("Reduce iteration {} - still {} tokens", 
                iteration + 1, estimateTokens(combinedKnowledge));
            
            // Split and reduce again
            List<String> parts = splitText(combinedKnowledge, MAX_TOKENS_PER_BATCH);
            List<CompletableFuture<String>> futures = parts.stream()
                .map(part -> CompletableFuture.supplyAsync(
                    () -> condenseKnowledge(part, question), 
                    executor))
                .toList();
            
            List<String> condensed = futures.stream()
                .map(CompletableFuture::join)
                .toList();
            
            combinedKnowledge = String.join("\n\n", condensed);
            totalLlmCalls += parts.size();
            iteration++;
        }
        
        // ============================================
        // PHASE 4: FINAL ANSWER GENERATION
        // ============================================
        String finalAnswer = generateFinalAnswer(
            question, 
            combinedKnowledge, 
            chatHistory
        );
        totalLlmCalls++;
        
        return QueryResult.builder()
            .answer(finalAnswer)
            .sources(extractSources(allChunks))
            .chunksUsed(allChunks.size())
            .llmCallsUsed(totalLlmCalls)
            .processingMode("map-reduce")
            .cacheHit(false)
            .build();
    }
    
    private List<ChunkBatch> createSmartBatches(
            List<DocumentChunkRepositoryCustom.ScoredChunk> chunks, 
            int maxTokensPerBatch) {
        
        // Group by document first
        Map<UUID, List<DocumentChunkRepositoryCustom.ScoredChunk>> byDocument = chunks.stream()
            .collect(Collectors.groupingBy(DocumentChunkRepositoryCustom.ScoredChunk::getDocumentId));
        
        List<ChunkBatch> batches = new ArrayList<>();
        List<DocumentChunkRepositoryCustom.ScoredChunk> currentBatch = new ArrayList<>();
        int currentTokens = 0;
        
        for (List<DocumentChunkRepositoryCustom.ScoredChunk> docChunks : byDocument.values()) {
            for (DocumentChunkRepositoryCustom.ScoredChunk chunk : docChunks) {
                int chunkTokens = chunk.getTokenCount() != null ? chunk.getTokenCount() : 
                    estimateTokens(chunk.getContent());
                
                // If adding this chunk exceeds limit, start new batch
                if (currentTokens + chunkTokens > maxTokensPerBatch && !currentBatch.isEmpty()) {
                    batches.add(new ChunkBatch(new ArrayList<>(currentBatch)));
                    currentBatch.clear();
                    currentTokens = 0;
                }
                
                currentBatch.add(chunk);
                currentTokens += chunkTokens;
            }
        }
        
        // Don't forget the last batch
        if (!currentBatch.isEmpty()) {
            batches.add(new ChunkBatch(currentBatch));
        }
        
        return batches;
    }
    
    private List<ExtractionResult> executeMapPhase(List<ChunkBatch> batches, String question) {
        Semaphore semaphore = new Semaphore(MAX_PARALLEL_CALLS);
        
        List<CompletableFuture<ExtractionResult>> futures = batches.stream()
            .map(batch -> CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        return extractFromBatch(batch, question);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during extraction", e);
                }
            }, executor))
            .toList();
        
        return futures.stream()
            .map(future -> {
                try {
                    return future.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("Extraction failed", e);
                    return ExtractionResult.empty();
                }
            })
            .filter(r -> !r.isEmpty())
            .toList();
    }
    
    private ExtractionResult extractFromBatch(ChunkBatch batch, String question) {
        try (var lease = keyManager.acquireKey(30_000)) {
            String chunksText = batch.getChunks().stream()
                .map(c -> String.format(
                    "[Source: %s, Page %d]\n%s\n",
                    c.getFileName(),
                    c.getPageNumber() != null ? c.getPageNumber() : 0,
                    c.getContent()))
                .collect(Collectors.joining("\n---\n"));
            
            String prompt = String.format("""
                TASK: Extract ALL information relevant to answering this question:
                "%s"
                
                FROM THESE DOCUMENTS:
                %s
                
                INSTRUCTIONS:
                1. Extract EVERY fact, number, name, and detail that could help answer the question
                2. Preserve exact values, configurations, and technical terms
                3. Include source references [Source: filename, Page X]
                4. If something seems partially relevant, INCLUDE IT
                5. Do NOT summarize or paraphrase - keep original detail
                
                OUTPUT FORMAT:
                Return a structured extraction with these sections:
                
                ## Key Facts
                - [fact with source]
                
                ## Technical Details
                - [specific configurations, code, commands]
                
                ## Related Context
                - [background information that might be useful]
                
                If nothing relevant found, return "NO_RELEVANT_INFO"
                """, question, chunksText);
            
            String response = geminiClient.generateContent(
                prompt, 
                "You are an expert information extraction assistant.",
                true,
                lease.getApiKey()
            );
            
            keyManager.reportSuccess(lease.getIdentifier());
            
            return parseExtractionResult(response, batch);
            
        } catch (Exception e) {
            log.error("Extraction failed for batch", e);
            return ExtractionResult.empty();
        }
    }
    
    private String executeReducePhase(List<ExtractionResult> extractions, String question) {
        if (extractions.isEmpty()) {
            return "No relevant information found in the documents.";
        }
        
        if (extractions.size() == 1) {
            return extractions.get(0).getContent();
        }
        
        String combined = extractions.stream()
            .map(ExtractionResult::getContent)
            .collect(Collectors.joining("\n\n---\n\n"));
        
        if (estimateTokens(combined) <= MAX_TOKENS_PER_BATCH) {
            return combined;
        }
        
        return condenseKnowledge(combined, question);
    }
    
    private String condenseKnowledge(String knowledge, String question) {
        try (var lease = keyManager.acquireKey(30_000)) {
            String prompt = String.format("""
                TASK: Merge and deduplicate this extracted information while PRESERVING ALL DETAILS.
                
                QUESTION CONTEXT: "%s"
                
                EXTRACTED INFORMATION:
                %s
                
                INSTRUCTIONS:
                1. Remove DUPLICATE information (same fact stated multiple ways)
                2. KEEP all unique facts, numbers, and technical details
                3. KEEP all source references
                4. Organize by topic/theme
                5. Do NOT remove information just because it seems less important
                
                OUTPUT: Merged, deduplicated knowledge (still detailed, not summarized)
                """, question, knowledge);
            
            String response = geminiClient.generateContent(
                prompt,
                "You are an expert at merging information while preserving details.",
                true,
                lease.getApiKey()
            );
            
            keyManager.reportSuccess(lease.getIdentifier());
            return response;
            
        } catch (Exception e) {
            log.error("Failed to condense knowledge", e);
            return knowledge;
        }
    }
    
    private String generateFinalAnswer(String question, String knowledge, List<QueryRequest.ChatMessage> chatHistory) {
        try (var lease = keyManager.acquireKey(30_000)) {
            String historyContext = "";
            if (chatHistory != null && !chatHistory.isEmpty()) {
                historyContext = chatHistory.stream()
                    .limit(10)
                    .map(m -> m.getRole() + ": " + m.getContent())
                    .collect(Collectors.joining("\n"));
                historyContext = "\nCONVERSATION HISTORY:\n" + historyContext + "\n";
            }
            
            String prompt = String.format("""
                Based on the following extracted knowledge from documents, answer the user's question.
                %s
                EXTRACTED KNOWLEDGE:
                %s
                
                USER QUESTION: %s
                
                INSTRUCTIONS:
                1. Answer comprehensively using the provided knowledge
                2. Cite sources using [Source: filename, Page X] format
                3. If the knowledge doesn't fully answer the question, say what's missing
                4. Be accurate - don't make up information not in the knowledge
                5. Structure your answer clearly with sections if needed
                
                ANSWER:
                """, historyContext, knowledge, question);
            
            String response = geminiClient.generateContent(
                prompt,
                "You are an expert assistant that provides accurate, well-sourced answers.",
                true,
                lease.getApiKey()
            );
            
            keyManager.reportSuccess(lease.getIdentifier());
            return response;
            
        } catch (Exception e) {
            log.error("Failed to generate final answer", e);
            throw new RuntimeException("Failed to generate answer", e);
        }
    }
    
    private int estimateTokens(String text) {
        return text.length() / 4; // Rough estimate: 1 token ≈ 4 characters
    }
    
    private List<String> splitText(String text, int maxTokens) {
        int maxChars = maxTokens * 4;
        List<String> parts = new ArrayList<>();
        
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            
            // Try to split at paragraph boundary
            if (end < text.length()) {
                int lastParagraph = text.lastIndexOf("\n\n", end);
                if (lastParagraph > start) {
                    end = lastParagraph;
                }
            }
            
            parts.add(text.substring(start, end));
            start = end;
        }
        
        return parts;
    }
    
    private ExtractionResult parseExtractionResult(String response, ChunkBatch batch) {
        if (response.contains("NO_RELEVANT_INFO")) {
            return ExtractionResult.empty();
        }
        
        return ExtractionResult.builder()
            .content(response)
            .sourceChunks(batch.getChunks())
            .build();
    }
    
    private List<QueryResult.Source> extractSources(List<DocumentChunkRepositoryCustom.ScoredChunk> chunks) {
        return chunks.stream()
            .map(c -> QueryResult.Source.builder()
                .documentId(c.getDocumentId())
                .fileName(c.getFileName())
                .pageNumber(c.getPageNumber())
                .slideNumber(c.getSlideNumber())
                .build())
            .distinct()
            .toList();
    }
    
    // Helper classes
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ChunkBatch {
        private List<DocumentChunkRepositoryCustom.ScoredChunk> chunks;
    }
    
    @lombok.Data
    @lombok.Builder
    public static class ExtractionResult {
        private String content;
        private List<DocumentChunkRepositoryCustom.ScoredChunk> sourceChunks;
        
        public static ExtractionResult empty() {
            return ExtractionResult.builder()
                .content("")
                .sourceChunks(List.of())
                .build();
        }
        
        public boolean isEmpty() {
            return content == null || content.isEmpty() || content.equals("NO_RELEVANT_INFO");
        }
    }
}

