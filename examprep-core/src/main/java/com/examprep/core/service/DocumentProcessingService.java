package com.examprep.core.service;

import com.examprep.common.constants.ProcessingStatus;
import com.examprep.core.model.ChunkingResult;
import com.examprep.core.model.ExtractionResult;
import com.examprep.core.processor.DocumentProcessor;
import com.examprep.core.processor.DocumentProcessorFactory;
import com.examprep.core.service.FileStorageService;
import com.examprep.data.entity.Document;
import com.examprep.data.entity.DocumentChunk;
import com.examprep.data.repository.DocumentChunkRepository;
import com.examprep.data.repository.DocumentRepository;
import com.examprep.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentProcessorFactory processorFactory;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final FileStorageService fileStorageService;
    private final com.examprep.llm.keymanager.ApiKeyManager apiKeyManager;

    // Thread pool for parallel chunk embedding (reused across all documents)
    private static final int CHUNK_PARALLEL_THREADS = 20; // Process 20 chunks in parallel per document
    private static final ExecutorService chunkExecutor = Executors.newFixedThreadPool(CHUNK_PARALLEL_THREADS);
    
    /**
     * Process document - split into transaction boundaries to avoid connection leaks
     * API calls are outside transactions to prevent holding DB connections during long operations
     */
    public void processDocument(UUID documentId) {
        // Initialize document status in transaction
        Document document = initializeProcessing(documentId);
        
        try {
            log.info("Starting processing for document: {}", document.getFileName());
            
            // Get processor for file type
            DocumentProcessor processor = processorFactory.getProcessor(document.getFileType());
            
            // Load file from storage with retry logic to handle race conditions
            InputStream fileStream = waitForFileAndGet(document.getId(), 5, 1000); // 5 retries, 1 second delay
            
            // Extract text from file
            ExtractionResult extractionResult = processor.extract(fileStream, document.getFileType());
            
            fileStream.close();
            
            // Chunk the content (no DB operations)
            List<ChunkingResult> chunks = chunkingService.chunkByPages(
                extractionResult.getPageContents(),
                extractionResult.getPageTitles()
            );
            
            // Assign this document to a specific API key based on document ID hash
            // This ensures parallel jobs use different keys simultaneously
            int numKeys = apiKeyManager.getKeyCount();
            int assignedKeyIndex = Math.abs(documentId.hashCode()) % numKeys;
            
            log.info("Processing document {} with API key {} ({} chunks)",
                document.getFileName(), assignedKeyIndex + 1, chunks.size());

            // Generate embeddings OUTSIDE transaction using assigned API key (API calls can take minutes)
            // Process chunks in PARALLEL for massive speedup (20 chunks at a time)
            UUID userId = document.getUser().getId();
            UUID chatId = document.getChat().getId();

            List<CompletableFuture<DocumentChunk>> chunkFutures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> {
                    try {
                        // Use assigned API key for all chunks in this document
                        float[] embedding = embeddingService.generateEmbedding(chunk.getContent(), assignedKeyIndex);

                        return DocumentChunk.builder()
                            .document(document)
                            .userId(userId)
                            .chatId(chatId)
                            .chunkIndex(chunk.getChunkIndex())
                            .content(chunk.getContent())
                            .contentHash(computeHash(chunk.getContent()))
                            .pageNumber(chunk.getPageNumber())
                            .slideNumber(chunk.getSlideNumber())
                            .sectionTitle(chunk.getSectionTitle())
                            .embedding(embedding)
                            .tokenCount(chunk.getTokenCount())
                            .build();
                    } catch (Exception e) {
                        log.error("Failed to process chunk {} for document {}: {}",
                            chunk.getChunkIndex(), documentId, e.getMessage());
                        throw new RuntimeException("Chunk processing failed", e);
                    }
                }, chunkExecutor))
                .collect(Collectors.toList());

            // Wait for all chunk embeddings to complete
            List<DocumentChunk> documentChunks = chunkFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
            
            // Save all chunks in a single transaction (batch save)
            saveChunksAndComplete(documentId, documentChunks, extractionResult.getTotalPages());
            
            log.info("Completed processing for document: {} - {} chunks created using API key {}", 
                document.getFileName(), chunks.size(), assignedKeyIndex + 1);
            
        } catch (Exception e) {
            log.error("Error processing document: {}", documentId, e);
            markAsFailed(documentId, e.getMessage());
            throw new RuntimeException("Failed to process document", e);
        }
    }
    
    @Transactional
    private Document initializeProcessing(UUID documentId) {
        // Load document once to get basic info (but don't load related entities)
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        // Delete existing chunks if reprocessing
        chunkRepository.deleteByDocumentId(documentId);
        log.debug("Deleted existing chunks for document: {}", documentId);
        
        // Update status to PROCESSING using native SQL to avoid loading related entities
        int updated = documentRepository.setProcessingStatus(documentId, java.time.Instant.now());
        if (updated == 0) {
            throw new RuntimeException("Document not found or could not be updated: " + documentId);
        }
        log.debug("Updated document {} status to PROCESSING via native SQL", documentId);
        
        return document;
    }
    
    @Transactional
    private void saveChunksAndComplete(UUID documentId, List<DocumentChunk> chunks, int totalPages) {
        // Use native SQL batch insert to avoid Hibernate vector mapping issues
        com.examprep.data.repository.DocumentChunkRepositoryCustom customRepo = 
            (com.examprep.data.repository.DocumentChunkRepositoryCustom) chunkRepository;
        customRepo.batchSaveChunksWithEmbeddings(chunks);
        
        // Update document status using native SQL to avoid loading entity and related chunks with vector fields
        int updated = documentRepository.setCompletedStatus(
            documentId,
            totalPages,
            chunks.size(),
            java.time.Instant.now()
        );
        
        if (updated == 0) {
            log.warn("Document {} not found when trying to mark as COMPLETED. Chunks were saved but document status was not updated.", documentId);
            throw new RuntimeException("Document not found: " + documentId);
        }
        
        log.debug("Updated document {} status to COMPLETED via native SQL ({} chunks, {} pages)", 
            documentId, chunks.size(), totalPages);
    }
    
    /**
     * Wait for file to be available with retry logic (fixes race condition)
     */
    private InputStream waitForFileAndGet(UUID documentId, int maxRetries, long delayMs) throws Exception {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (fileStorageService.fileExists(documentId)) {
                try {
                    InputStream stream = fileStorageService.getFile(documentId);
                    log.debug("File found for document {} after {} attempt(s)", documentId, attempt);
                    return stream;
                } catch (Exception e) {
                    log.warn("File exists but failed to open for document {} (attempt {}/{}): {}", 
                        documentId, attempt, maxRetries, e.getMessage());
                    if (attempt == maxRetries) {
                        throw e;
                    }
                }
            } else {
                log.warn("File not found for document {} (attempt {}/{})", documentId, attempt, maxRetries);
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(delayMs * attempt); // Exponential backoff
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for file", e);
                    }
                }
            }
        }
        
        throw new RuntimeException("File not found in storage for document: " + documentId + " after " + maxRetries + " attempts");
    }
    
    @Transactional
    private void markAsFailed(UUID documentId, String errorMessage) {
        // Use native SQL update to avoid loading entity and related chunks with vector fields
        String truncatedError = errorMessage != null && errorMessage.length() > 2000 
            ? errorMessage.substring(0, 2000) 
            : errorMessage;
        
        int updated = documentRepository.setFailedStatus(documentId, truncatedError);
        
        if (updated == 0) {
            log.warn("Document {} not found when trying to mark as FAILED. Error message: {}", documentId, truncatedError);
            // Don't throw exception - document might have been deleted, just log it
        } else {
            log.debug("Updated document {} status to FAILED via native SQL", documentId);
        }
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
}

