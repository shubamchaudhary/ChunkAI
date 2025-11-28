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
    
    @Transactional
    public void processDocument(UUID documentId) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        try {
            log.info("Starting processing for document: {}", document.getFileName());
            
            // Delete existing chunks if reprocessing (prevents duplicate key violations)
            chunkRepository.deleteByDocumentId(documentId);
            log.debug("Deleted existing chunks for document: {}", documentId);
            
            // Update status to PROCESSING
            document.setProcessingStatus(ProcessingStatus.PROCESSING);
            document.setProcessingStartedAt(java.time.Instant.now());
            documentRepository.save(document);
            
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
            
            document.setTotalPages(extractionResult.getTotalPages());
            
            // Chunk the content
            List<ChunkingResult> chunks = chunkingService.chunkByPages(
                extractionResult.getPageContents(),
                extractionResult.getPageTitles()
            );
            
            // Generate embeddings and save chunks
            for (ChunkingResult chunk : chunks) {
                float[] embedding = embeddingService.generateEmbedding(chunk.getContent());
                
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
                    .embedding(embedding)
                    .tokenCount(chunk.getTokenCount())
                    .build();
                
                chunkRepository.save(documentChunk);
            }
            
            // Update document status
            document.setTotalChunks(chunks.size());
            document.setProcessingStatus(ProcessingStatus.COMPLETED);
            document.setProcessingCompletedAt(java.time.Instant.now());
            documentRepository.save(document);
            
            log.info("Completed processing for document: {} - {} chunks created", 
                document.getFileName(), chunks.size());
            
        } catch (Exception e) {
            log.error("Error processing document: {}", documentId, e);
            document.setProcessingStatus(ProcessingStatus.FAILED);
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);
            throw new RuntimeException("Failed to process document", e);
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

