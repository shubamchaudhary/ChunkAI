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
            
            // Load file from storage
            if (!fileStorageService.fileExists(document.getId())) {
                throw new RuntimeException("File not found in storage for document: " + document.getId());
            }
            
            InputStream fileStream = fileStorageService.getFile(document.getId());
            
            // Extract text from file
            ExtractionResult extractionResult = processor.extract(fileStream, document.getFileType());
            
            fileStream.close();
            
            // Chunk the content (no DB operations)
            List<ChunkingResult> chunks = chunkingService.chunkByPages(
                extractionResult.getPageContents(),
                extractionResult.getPageTitles()
            );
            
            // Generate embeddings OUTSIDE transaction (API calls can take minutes)
            List<DocumentChunk> documentChunks = new java.util.ArrayList<>();
            UUID userId = document.getUser().getId();
            UUID chatId = document.getChat().getId();
            
            for (ChunkingResult chunk : chunks) {
                float[] embedding = embeddingService.generateEmbedding(chunk.getContent());
                
                DocumentChunk documentChunk = DocumentChunk.builder()
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
                
                documentChunks.add(documentChunk);
            }
            
            // Save all chunks in a single transaction (batch save)
            saveChunksAndComplete(documentId, documentChunks, extractionResult.getTotalPages());
            
            log.info("Completed processing for document: {} - {} chunks created", 
                document.getFileName(), chunks.size());
            
        } catch (Exception e) {
            log.error("Error processing document: {}", documentId, e);
            markAsFailed(documentId, e.getMessage());
            throw new RuntimeException("Failed to process document", e);
        }
    }
    
    @Transactional
    private Document initializeProcessing(UUID documentId) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        // Delete existing chunks if reprocessing
        chunkRepository.deleteByDocumentId(documentId);
        log.debug("Deleted existing chunks for document: {}", documentId);
        
        // Update status to PROCESSING
        document.setProcessingStatus(ProcessingStatus.PROCESSING);
        document.setProcessingStartedAt(java.time.Instant.now());
        documentRepository.save(document);
        
        return document;
    }
    
    @Transactional
    private void saveChunksAndComplete(UUID documentId, List<DocumentChunk> chunks, int totalPages) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        // Batch save all chunks
        chunkRepository.saveAll(chunks);
        
        // Update document status
        document.setTotalPages(totalPages);
        document.setTotalChunks(chunks.size());
        document.setProcessingStatus(ProcessingStatus.COMPLETED);
        document.setProcessingCompletedAt(java.time.Instant.now());
        documentRepository.save(document);
    }
    
    @Transactional
    private void markAsFailed(UUID documentId, String errorMessage) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        document.setProcessingStatus(ProcessingStatus.FAILED);
        document.setErrorMessage(errorMessage);
        documentRepository.save(document);
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

