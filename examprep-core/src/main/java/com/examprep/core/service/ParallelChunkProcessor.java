package com.examprep.core.service;

import com.examprep.core.model.ChunkingResult;
import com.examprep.data.entity.Document;
import com.examprep.data.entity.DocumentChunk;
import com.examprep.data.repository.DocumentChunkRepository;
import com.examprep.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Processes document chunks in parallel with rate limiting.
 * Respects Gemini embedding rate limits (8 RPM) and processes chunks concurrently.
 * As chunks finish, new ones are started immediately if rate limit allows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParallelChunkProcessor {
    
    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository chunkRepository;
    
    @Value("${gemini.requests-per-minute:8}")
    private int maxRequestsPerMinute;
    
    // Thread pool for parallel chunk processing
    // Use a pool size larger than RPM to allow queuing when rate limit is hit
    // ApiKeyManager will handle actual rate limiting and blocking
    private static final int THREAD_POOL_SIZE = 30; // Larger than RPM to allow queuing
    private final ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    
    /**
     * Process all chunks in parallel batches.
     * Respects rate limits by using a semaphore.
     * As chunks complete, new ones start immediately if capacity allows.
     */
    public void processChunksInParallel(
        UUID documentId,
        Document document,
        List<ChunkingResult> chunks
    ) {
        long startTime = System.currentTimeMillis();
        String processId = "chunks-" + documentId.toString().substring(0, 8) + "-" + System.currentTimeMillis();
        
        log.info("[PARALLEL_CHUNKS] Starting parallel chunk processing | processId={} | documentId={} | totalChunks={} | maxRpm={} | threadPoolSize={}", 
            processId, documentId, chunks.size(), maxRequestsPerMinute, THREAD_POOL_SIZE);
        
        if (chunks.isEmpty()) {
            log.warn("[PARALLEL_CHUNKS] No chunks to process | processId={}", processId);
            return;
        }
        
        // Create futures for all chunks
        List<CompletableFuture<ChunkProcessingResult>> futures = chunks.stream()
            .map(chunk -> processChunkAsync(processId, documentId, document, chunk))
            .collect(Collectors.toList());
        
        // Track progress
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        
        // Wait for all chunks to complete and track progress
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        
        // Log progress periodically
        CompletableFuture.runAsync(() -> {
            while (!allFutures.isDone()) {
                try {
                    Thread.sleep(5000); // Log every 5 seconds
                    int completed = completedCount.get();
                    int failed = failedCount.get();
                    int inProgress = chunks.size() - completed - failed;
                    log.info("[PARALLEL_CHUNKS] Progress update | processId={} | completed={} | failed={} | inProgress={} | total={}", 
                        processId, completed, failed, inProgress, chunks.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        // Process results as they complete
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<ChunkProcessingResult> future = futures.get(i);
            try {
                ChunkProcessingResult result = future.join(); // Wait for this chunk
                if (result.isSuccess()) {
                    completedCount.incrementAndGet();
                    log.debug("[PARALLEL_CHUNKS] Chunk completed | processId={} | chunkIndex={} | durationMs={}", 
                        processId, result.getChunkIndex(), result.getDurationMs());
                } else {
                    failedCount.incrementAndGet();
                    log.error("[PARALLEL_CHUNKS] Chunk failed | processId={} | chunkIndex={} | error={}", 
                        processId, result.getChunkIndex(), result.getError());
                }
            } catch (Exception e) {
                failedCount.incrementAndGet();
                log.error("[PARALLEL_CHUNKS] Chunk processing exception | processId={} | chunkIndex={} | error={}", 
                    processId, chunks.get(i).getChunkIndex(), e.getMessage(), e);
            }
        }
        
        long totalDuration = System.currentTimeMillis() - startTime;
        int completed = completedCount.get();
        int failed = failedCount.get();
        
        log.info("[PARALLEL_CHUNKS] Parallel chunk processing completed | processId={} | documentId={} | totalChunks={} | completed={} | failed={} | totalDurationMs={} | avgTimePerChunkMs={}", 
            processId, documentId, chunks.size(), completed, failed, totalDuration,
            chunks.isEmpty() ? 0 : totalDuration / chunks.size());
        
        if (failed > 0) {
            throw new RuntimeException(
                String.format("Failed to process %d out of %d chunks", failed, chunks.size())
            );
        }
    }
    
    /**
     * Process a single chunk asynchronously with rate limiting.
     */
    private CompletableFuture<ChunkProcessingResult> processChunkAsync(
        String processId,
        UUID documentId,
        Document document,
        ChunkingResult chunk
    ) {
        return CompletableFuture.supplyAsync(() -> {
            long chunkStartTime = System.currentTimeMillis();
            int chunkIndex = chunk.getChunkIndex();
            
            try {
                // Generate embedding (API call) - ApiKeyManager will handle rate limiting automatically
                long embeddingStartTime = System.currentTimeMillis();
                log.debug("[PARALLEL_CHUNKS] Generating embedding | processId={} | chunkIndex={} | contentLength={}", 
                    processId, chunkIndex, chunk.getContent().length());
                
                // ApiKeyManager will block/wait if rate limit is reached
                float[] embedding = embeddingService.generateEmbedding(chunk.getContent());
                
                long embeddingDuration = System.currentTimeMillis() - embeddingStartTime;
                log.debug("[PARALLEL_CHUNKS] Embedding generated | processId={} | chunkIndex={} | embeddingDurationMs={}", 
                    processId, chunkIndex, embeddingDuration);
                
                // Save chunk to database (short transaction)
                long saveStartTime = System.currentTimeMillis();
                saveChunkInTransaction(documentId, document, chunk, embedding);
                long saveDuration = System.currentTimeMillis() - saveStartTime;
                
                long totalDuration = System.currentTimeMillis() - chunkStartTime;
                
                log.debug("[PARALLEL_CHUNKS] Chunk saved | processId={} | chunkIndex={} | saveDurationMs={} | totalDurationMs={}", 
                    processId, chunkIndex, saveDuration, totalDuration);
                
                return new ChunkProcessingResult(chunkIndex, true, totalDuration, null);
                
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - chunkStartTime;
                log.error("[PARALLEL_CHUNKS] Chunk processing failed | processId={} | chunkIndex={} | durationMs={} | error={}", 
                    processId, chunkIndex, duration, e.getMessage(), e);
                return new ChunkProcessingResult(chunkIndex, false, duration, e.getMessage());
            }
        }, executorService);
    }
    
    /**
     * Save a single chunk in a short transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void saveChunkInTransaction(
        UUID documentId,
        Document document,
        ChunkingResult chunk,
        float[] embedding
    ) {
        // Extract key terms
        String[] keyTerms = extractKeyTerms(chunk.getContent());
        
        DocumentChunk documentChunk = DocumentChunk.builder()
            .document(document)
            .userId(document.getUser().getId())
            .chatId(document.getChat().getId())
            .chunkIndex(chunk.getChunkIndex())
            .content(chunk.getContent())
            .contentHash(computeHash(chunk.getContent()))
            .pageNumber(chunk.getPageNumber())
            .slideNumber(chunk.getSlideNumber())
            .sectionTitle(chunk.getSectionTitle())
            .chunkType(determineChunkType(chunk.getContent()))
            .keyTerms(keyTerms)
            .embedding(embedding)
            .tokenCount(chunk.getTokenCount())
            .build();
        
        chunkRepository.save(documentChunk);
    }
    
    /**
     * Result class for chunk processing.
     */
    @lombok.Value
    private static class ChunkProcessingResult {
        int chunkIndex;
        boolean success;
        long durationMs;
        String error;
    }
    
    private String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String[] extractKeyTerms(String content) {
        List<String> stopWords = List.of("the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was", "one", "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old", "see", "two", "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too", "use");
        
        String[] words = content.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .split("\\s+");
        
        return java.util.Arrays.stream(words)
            .filter(w -> w.length() > 4)
            .filter(w -> !stopWords.contains(w))
            .distinct()
            .limit(10)
            .toArray(String[]::new);
    }
    
    private String determineChunkType(String content) {
        String lower = content.toLowerCase().trim();
        
        if (lower.length() < 100 && !lower.contains(".") && !lower.contains(",")) {
            return "heading";
        }
        if (lower.contains("```") || lower.contains("public class") || lower.contains("function")) {
            return "code";
        }
        if (lower.contains("|") && lower.split("\\|").length > 3) {
            return "table";
        }
        if (lower.startsWith("*") || lower.startsWith("-") || lower.startsWith("1.")) {
            return "list";
        }
        return "paragraph";
    }
}

