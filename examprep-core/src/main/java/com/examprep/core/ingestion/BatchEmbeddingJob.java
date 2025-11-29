package com.examprep.core.ingestion;

import com.examprep.common.constants.ProcessingStatus;
import com.examprep.data.entity.DocumentChunk;
import com.examprep.data.repository.DocumentChunkRepository;
import com.examprep.data.repository.DocumentRepository;
import com.examprep.llm.embedding.GeminiBatchEmbeddingService;
import com.examprep.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Background job that processes chunks without embeddings in batches.
 * Uses Gemini batch embedding API to process up to 100 chunks per API call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchEmbeddingJob {
    
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final GeminiBatchEmbeddingService batchEmbeddingService;
    private final EmbeddingService embeddingService; // For vector string conversion
    
    @Value("${batch.embedding.enabled:true}")
    private boolean enabled;
    
    @Value("${batch.embedding.max-chunks-per-run:500}")
    private int maxChunksPerRun;
    
    @Value("${gemini.embedding.batch-size:100}")
    private int batchSize;
    
    /**
     * Run batch embedding job every 5 seconds.
     * Processes chunks in batches of 100, respects rate limits.
     */
    @Scheduled(fixedDelayString = "${batch.embedding.job-interval-ms:5000}")
    public void processPendingEmbeddings() {
        if (!enabled) {
            log.debug("[BATCH_EMBED_JOB] Batch embedding job is disabled");
            return;
        }
        
        long jobStartTime = System.currentTimeMillis();
        String jobId = "job-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        
        log.info("[BATCH_EMBED_JOB] Starting batch embedding job | jobId={} | maxChunksPerRun={}", 
            jobId, maxChunksPerRun);
        
        try {
            // Find chunks without embeddings
            List<DocumentChunk> pendingChunks = chunkRepository.findChunksWithoutEmbeddings(maxChunksPerRun);
            
            if (pendingChunks.isEmpty()) {
                log.debug("[BATCH_EMBED_JOB] No pending chunks to process | jobId={}", jobId);
                return;
            }
            
            log.info("[BATCH_EMBED_JOB] Found pending chunks | jobId={} | totalChunks={}", 
                jobId, pendingChunks.size());
            
            // Group chunks by document for progress tracking
            Map<UUID, List<DocumentChunk>> chunksByDocument = pendingChunks.stream()
                .collect(Collectors.groupingBy(chunk -> chunk.getDocument().getId()));
            
            log.info("[BATCH_EMBED_JOB] Processing chunks for {} documents | jobId={}", 
                chunksByDocument.size(), jobId);
            
            // Process chunks in batches
            int totalProcessed = 0;
            int totalFailed = 0;
            
            // Group all chunks into batches of batchSize (100)
            for (int i = 0; i < pendingChunks.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, pendingChunks.size());
                List<DocumentChunk> batch = pendingChunks.subList(i, endIndex);
                
                int batchNumber = (i / batchSize) + 1;
                int totalBatches = (pendingChunks.size() + batchSize - 1) / batchSize;
                
                log.info("[BATCH_EMBED_JOB] Processing batch | jobId={} | batchNumber={}/{} | chunksInBatch={}", 
                    jobId, batchNumber, totalBatches, batch.size());
                
                try {
                    // Extract texts for embedding
                    List<String> texts = batch.stream()
                        .map(DocumentChunk::getContent)
                        .collect(Collectors.toList());
                    
                    // Call batch embedding API
                    long batchStartTime = System.currentTimeMillis();
                    List<float[]> embeddings = batchEmbeddingService.generateBatchEmbeddings(texts);
                    long batchDuration = System.currentTimeMillis() - batchStartTime;
                    
                    if (embeddings.size() != batch.size()) {
                        throw new RuntimeException(
                            String.format("Embedding count mismatch: expected %d, got %d", batch.size(), embeddings.size())
                        );
                    }
                    
                    // Update chunks with embeddings
                    int batchProcessed = 0;
                    for (int j = 0; j < batch.size(); j++) {
                        DocumentChunk chunk = batch.get(j);
                        float[] embedding = embeddings.get(j);
                        
                        try {
                            updateChunkEmbedding(chunk.getId(), embedding);
                            batchProcessed++;
                            totalProcessed++;
                        } catch (Exception e) {
                            log.error("[BATCH_EMBED_JOB] Failed to update chunk embedding | jobId={} | chunkId={} | error={}", 
                                jobId, chunk.getId(), e.getMessage(), e);
                            totalFailed++;
                        }
                    }
                    
                    log.info("[BATCH_EMBED_JOB] Batch completed | jobId={} | batchNumber={}/{} | processed={} | failed={} | durationMs={}", 
                        jobId, batchNumber, totalBatches, batchProcessed, batch.size() - batchProcessed, batchDuration);
                    
                    // Update document progress after each batch
                    updateDocumentProgress(chunksByDocument.keySet());
                    
                } catch (Exception e) {
                    log.error("[BATCH_EMBED_JOB] Batch processing failed | jobId={} | batchNumber={}/{} | error={}", 
                        jobId, batchNumber, totalBatches, e.getMessage(), e);
                    totalFailed += batch.size();
                    // Continue with next batch
                }
                
                // Rate limiting: wait 1 second between batches
                if (i + batchSize < pendingChunks.size()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[BATCH_EMBED_JOB] Job interrupted | jobId={}", jobId);
                        return;
                    }
                }
            }
            
            // Final progress update
            updateDocumentProgress(chunksByDocument.keySet());
            
            long jobDuration = System.currentTimeMillis() - jobStartTime;
            log.info("[BATCH_EMBED_JOB] Batch embedding job completed | jobId={} | totalProcessed={} | totalFailed={} | durationMs={}", 
                jobId, totalProcessed, totalFailed, jobDuration);
            
        } catch (Exception e) {
            long jobDuration = System.currentTimeMillis() - jobStartTime;
            log.error("[BATCH_EMBED_JOB] Batch embedding job failed | jobId={} | durationMs={} | error={}", 
                jobId, jobDuration, e.getMessage(), e);
        }
    }
    
    /**
     * Update chunk embedding in a short transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void updateChunkEmbedding(UUID chunkId, float[] embedding) {
        String embeddingString = embeddingService.toVectorString(embedding);
        chunkRepository.updateChunkEmbedding(chunkId, embeddingString);
    }
    
    /**
     * Update document progress (chunks_embedded count and processing tier).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void updateDocumentProgress(Set<UUID> documentIds) {
        for (UUID documentId : documentIds) {
            try {
                if (!documentRepository.existsById(documentId)) {
                    continue;
                }
                
                long totalChunks = chunkRepository.countByDocumentId(documentId);
                long embeddedChunks = totalChunks - chunkRepository.countChunksWithoutEmbeddingsByDocumentId(documentId);
                
                // Update progress
                String processingTier;
                if (embeddedChunks == 0) {
                    processingTier = "CHUNKED";
                } else if (embeddedChunks < totalChunks) {
                    processingTier = "EMBEDDING";
                } else {
                    processingTier = "COMPLETED";
                    // Also update processing_status
                    updateDocumentCompletionStatus(documentId);
                }
                
                // Use native query to update without loading entity
                documentRepository.updateDocumentProgress(
                    documentId,
                    processingTier,
                    (int) embeddedChunks,
                    (int) totalChunks
                );
                
                log.debug("[BATCH_EMBED_JOB] Updated document progress | documentId={} | tier={} | embedded={}/{}", 
                    documentId, processingTier, embeddedChunks, totalChunks);
                    
            } catch (Exception e) {
                log.error("[BATCH_EMBED_JOB] Failed to update document progress | documentId={} | error={}", 
                    documentId, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Mark document as completed when all chunks are embedded.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void updateDocumentCompletionStatus(UUID documentId) {
        documentRepository.updateProcessingStatus(
            documentId,
            ProcessingStatus.COMPLETED.name(),
            null,
            Instant.now(),
            null,
            null,
            null
        );
    }
}

