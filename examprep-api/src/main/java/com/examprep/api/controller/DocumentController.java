package com.examprep.api.controller;

import com.examprep.api.dto.response.DocumentResponse;
import com.examprep.common.constants.FileTypes;
import com.examprep.common.constants.ProcessingStatus;
import com.examprep.common.util.FileUtils;
import com.examprep.core.service.FileStorageService;
import com.examprep.data.entity.Document;
import com.examprep.data.entity.ProcessingJob;
import com.examprep.data.entity.User;
import com.examprep.data.repository.DocumentRepository;
import com.examprep.data.repository.ProcessingJobRepository;
import com.examprep.data.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {
    
    private final DocumentRepository documentRepository;
    private final ProcessingJobRepository jobRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            Authentication authentication,
            HttpServletRequest request
    ) {
        log.debug("Upload request received. Content-Type: {}", request.getContentType());
        log.debug("File received: name={}, size={}, contentType={}", 
            file != null ? file.getOriginalFilename() : "null",
            file != null ? file.getSize() : 0,
            file != null ? file.getContentType() : "null");
        
        if (file == null || file.isEmpty()) {
            log.error("File is null or empty. Content-Type: {}", request.getContentType());
            return ResponseEntity.badRequest().build();
        }
        
        UUID userId = UUID.fromString(authentication.getName());
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (!FileUtils.isValidFile(file.getOriginalFilename(), file.getSize())) {
            return ResponseEntity.badRequest().build();
        }
        
        String extension = FileUtils.getFileExtension(file.getOriginalFilename());
        String sanitizedFileName = FileUtils.sanitizeFileName(file.getOriginalFilename());
        
        // Create document record first to get ID
        Document document = Document.builder()
            .user(user)
            .fileName(sanitizedFileName)
            .originalFileName(file.getOriginalFilename())
            .fileType(extension)
            .fileSizeBytes(file.getSize())
            .mimeType(file.getContentType())
            .processingStatus(ProcessingStatus.PENDING)
            .build();
        
        document = documentRepository.save(document);
        
        // Save file to storage
        try {
            fileStorageService.saveFile(file, document.getId());
        } catch (Exception e) {
            // If file save fails, delete document record
            documentRepository.delete(document);
            throw new RuntimeException("Failed to save file", e);
        }
        
        // Create processing job
        ProcessingJob job = ProcessingJob.builder()
            .document(document)
            .status("QUEUED")
            .priority(5)
            .build();
        jobRepository.save(job);
        
        DocumentResponse response = DocumentResponse.builder()
            .id(document.getId())
            .fileName(document.getFileName())
            .fileType(document.getFileType())
            .fileSizeBytes(document.getFileSizeBytes())
            .processingStatus(document.getProcessingStatus())
            .createdAt(document.getCreatedAt())
            .build();
        
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
    
    @GetMapping
    public ResponseEntity<Page<DocumentResponse>> getDocuments(
            @RequestParam(required = false) ProcessingStatus status,
            Pageable pageable,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        
        Page<Document> documents;
        if (status != null) {
            documents = documentRepository.findByUserIdAndProcessingStatus(userId, status, pageable);
        } else {
            documents = documentRepository.findByUserId(userId, pageable);
        }
        
        Page<DocumentResponse> response = documents.map(doc -> DocumentResponse.builder()
            .id(doc.getId())
            .fileName(doc.getFileName())
            .fileType(doc.getFileType())
            .fileSizeBytes(doc.getFileSizeBytes())
            .totalPages(doc.getTotalPages())
            .totalChunks(doc.getTotalChunks())
            .processingStatus(doc.getProcessingStatus())
            .errorMessage(doc.getErrorMessage())
            .createdAt(doc.getCreatedAt())
            .processingCompletedAt(doc.getProcessingCompletedAt())
            .build());
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> getDocument(
            @PathVariable UUID documentId,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
            .orElse(null);
        
        if (document == null) {
            return ResponseEntity.notFound().build();
        }
        
        DocumentResponse response = DocumentResponse.builder()
            .id(document.getId())
            .fileName(document.getFileName())
            .fileType(document.getFileType())
            .fileSizeBytes(document.getFileSizeBytes())
            .totalPages(document.getTotalPages())
            .totalChunks(document.getTotalChunks())
            .processingStatus(document.getProcessingStatus())
            .errorMessage(document.getErrorMessage())
            .createdAt(document.getCreatedAt())
            .processingCompletedAt(document.getProcessingCompletedAt())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable UUID documentId,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
            .orElse(null);
        
        if (document == null) {
            return ResponseEntity.notFound().build();
        }
        
        // Delete file from storage
        try {
            fileStorageService.deleteFile(documentId);
        } catch (Exception e) {
            log.warn("Failed to delete file for document {}", documentId, e);
        }
        
        documentRepository.delete(document);
        return ResponseEntity.noContent().build();
    }
    
    @PostMapping("/upload/bulk")
    public ResponseEntity<BulkUploadResponse> uploadBulkDocuments(
            @RequestParam("files") MultipartFile[] files,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (files.length > 20) {
            return ResponseEntity.badRequest().build();
        }
        
        List<DocumentResponse> uploads = new ArrayList<>();
        int queuedCount = 0;
        
        for (MultipartFile file : files) {
            if (!FileUtils.isValidFile(file.getOriginalFilename(), file.getSize())) {
                continue;
            }
            
            try {
                String extension = FileUtils.getFileExtension(file.getOriginalFilename());
                String sanitizedFileName = FileUtils.sanitizeFileName(file.getOriginalFilename());
                
                Document document = Document.builder()
                    .user(user)
                    .fileName(sanitizedFileName)
                    .originalFileName(file.getOriginalFilename())
                    .fileType(extension)
                    .fileSizeBytes(file.getSize())
                    .mimeType(file.getContentType())
                    .processingStatus(ProcessingStatus.PENDING)
                    .build();
                
                document = documentRepository.save(document);
                
                // Save file
                fileStorageService.saveFile(file, document.getId());
                
                // Create processing job
                ProcessingJob job = ProcessingJob.builder()
                    .document(document)
                    .status("QUEUED")
                    .priority(5)
                    .build();
                jobRepository.save(job);
                queuedCount++;
                
                uploads.add(DocumentResponse.builder()
                    .id(document.getId())
                    .fileName(document.getFileName())
                    .fileType(document.getFileType())
                    .fileSizeBytes(document.getFileSizeBytes())
                    .processingStatus(document.getProcessingStatus())
                    .createdAt(document.getCreatedAt())
                    .build());
            } catch (Exception e) {
                log.error("Failed to upload file: {}", file.getOriginalFilename(), e);
            }
        }
        
        BulkUploadResponse response = new BulkUploadResponse(uploads, queuedCount);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
    
    @GetMapping("/{documentId}/status")
    public ResponseEntity<DocumentStatusResponse> getDocumentStatus(
            @PathVariable UUID documentId,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
            .orElse(null);
        
        if (document == null) {
            return ResponseEntity.notFound().build();
        }
        
        int progress = 0;
        if (document.getProcessingStatus() == ProcessingStatus.COMPLETED) {
            progress = 100;
        } else if (document.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            // Estimate progress based on chunks processed
            if (document.getTotalPages() != null && document.getTotalPages() > 0) {
                progress = Math.min(90, (document.getTotalChunks() * 100) / document.getTotalPages());
            } else {
                progress = 50; // Unknown, assume halfway
            }
        }
        
        DocumentStatusResponse response = DocumentStatusResponse.builder()
            .status(document.getProcessingStatus().toString())
            .progress(progress)
            .chunksProcessed(document.getTotalChunks())
            .totalChunks(document.getTotalChunks())
            .estimatedTimeRemaining(null) // Could calculate based on average processing time
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    // Inner classes for responses
    private static class BulkUploadResponse {
        private List<DocumentResponse> uploads;
        private int totalQueued;
        
        public BulkUploadResponse(List<DocumentResponse> uploads, int totalQueued) {
            this.uploads = uploads;
            this.totalQueued = totalQueued;
        }
        
        public List<DocumentResponse> getUploads() { return uploads; }
        public int getTotalQueued() { return totalQueued; }
    }
    
    private static class DocumentStatusResponse {
        private String status;
        private int progress;
        private Integer chunksProcessed;
        private Integer totalChunks;
        private Integer estimatedTimeRemaining;
        
        @lombok.Builder
        public DocumentStatusResponse(String status, int progress, Integer chunksProcessed, 
                                     Integer totalChunks, Integer estimatedTimeRemaining) {
            this.status = status;
            this.progress = progress;
            this.chunksProcessed = chunksProcessed;
            this.totalChunks = totalChunks;
            this.estimatedTimeRemaining = estimatedTimeRemaining;
        }
        
        public String getStatus() { return status; }
        public int getProgress() { return progress; }
        public Integer getChunksProcessed() { return chunksProcessed; }
        public Integer getTotalChunks() { return totalChunks; }
        public Integer getEstimatedTimeRemaining() { return estimatedTimeRemaining; }
    }
}

