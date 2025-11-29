package com.examprep.data.repository;

import com.examprep.common.constants.ProcessingStatus;
import com.examprep.data.entity.Document;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@Slf4j
public class DocumentRepositoryImpl implements DocumentRepositoryCustom {
    
    @PersistenceContext
    private EntityManager entityManager;
    
    /**
     * DEPRECATED: Document summaries removed - no longer supported.
     * Returns empty list. Use HybridSearchService for chunk-level search instead.
     */
    @Deprecated
    public List<Document> findSimilarDocumentsBySummary(
        UUID chatId,
        String queryEmbedding,
        int limit
    ) {
        log.warn("[DEPRECATED] findSimilarDocumentsBySummary called - document summaries are no longer supported. Returning empty list.");
        return new ArrayList<>();
    }
    
    /**
     * Find documents by chat ID (new schema without summary fields).
     */
    public List<Document> findByChatIdExcludingEmbedding(UUID chatId) {
        String sql = """
            SELECT id, user_id, chat_id, file_name, original_file_name,
                   file_type, file_size_bytes, mime_type, total_pages, total_chunks,
                   processing_status, processing_started_at, processing_completed_at,
                   error_message, processing_tier, chunks_embedded, created_at, updated_at
            FROM documents
            WHERE chat_id = :chatId
            ORDER BY created_at DESC
            """;
        
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("chatId", chatId);
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        List<Document> documents = new ArrayList<>();
        for (Object[] row : results) {
            try {
                documents.add(buildDocumentFromRow(row));
            } catch (Exception e) {
                log.warn("Failed to construct document from row, skipping: {}", e.getMessage());
            }
        }
        
        return documents;
    }
    
    /**
     * Find document by ID (new schema without summary fields).
     */
    public java.util.Optional<Document> findByIdExcludingEmbedding(UUID documentId) {
        String sql = """
            SELECT id, user_id, chat_id, file_name, original_file_name,
                   file_type, file_size_bytes, mime_type, total_pages, total_chunks,
                   processing_status, processing_started_at, processing_completed_at,
                   error_message, processing_tier, chunks_embedded, created_at, updated_at
            FROM documents
            WHERE id = :id
            """;
        
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("id", documentId);
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        if (results.isEmpty()) {
            return java.util.Optional.empty();
        }
        
        try {
            Object[] row = results.get(0);
            return java.util.Optional.of(buildDocumentFromRow(row));
        } catch (Exception e) {
            log.warn("Failed to construct document from row for id {}, returning empty", documentId, e);
            return java.util.Optional.empty();
        }
    }
    
    /**
     * Helper method to build Document entity from native query row (new schema without summary fields).
     */
    private Document buildDocumentFromRow(Object[] row) {
        UUID id = (UUID) row[0];
        UUID userId = (UUID) row[1];
        UUID chatIdFromRow = (UUID) row[2];
        String fileName = (String) row[3];
        String originalFileName = (String) row[4];
        String fileType = (String) row[5];
        Long fileSizeBytes = row[6] != null ? ((Number) row[6]).longValue() : null;
        String mimeType = (String) row[7];
        Integer totalPages = row[8] != null ? ((Number) row[8]).intValue() : null;
        Integer totalChunks = row[9] != null ? ((Number) row[9]).intValue() : null;
        String processingStatus = (String) row[10];
        
        java.time.Instant processingStartedAt = convertToInstant(row[11]);
        java.time.Instant processingCompletedAt = convertToInstant(row[12]);
        String errorMessage = (String) row[13];
        String processingTier = (String) row[14];
        Integer chunksEmbedded = row[15] != null ? ((Number) row[15]).intValue() : null;
        java.time.Instant createdAt = convertToInstant(row[16]);
        java.time.Instant updatedAt = convertToInstant(row[17]);
        
        Document doc = Document.builder()
            .id(id)
            .fileName(fileName)
            .originalFileName(originalFileName)
            .fileType(fileType)
            .fileSizeBytes(fileSizeBytes)
            .mimeType(mimeType)
            .totalPages(totalPages)
            .totalChunks(totalChunks != null ? totalChunks : 0)
            .processingStatus(parseProcessingStatus(processingStatus))
            .processingStartedAt(processingStartedAt)
            .processingCompletedAt(processingCompletedAt)
            .errorMessage(errorMessage)
            .processingTier(processingTier != null ? processingTier : "PENDING")
            .chunksEmbedded(chunksEmbedded != null ? chunksEmbedded : 0)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build();
        
        doc.setUser(entityManager.getReference(com.examprep.data.entity.User.class, userId));
        doc.setChat(entityManager.getReference(com.examprep.data.entity.Chat.class, chatIdFromRow));
        
        return doc;
    }
    
    /**
     * Helper method to convert various timestamp types to Instant.
     */
    private java.time.Instant convertToInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.time.Instant) {
            return (java.time.Instant) value;
        } else if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toInstant();
        } else if (value instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) value).toInstant();
        }
        return null;
    }
    
    /**
     * Safely parse ProcessingStatus from string, defaulting to PENDING on error.
     */
    private ProcessingStatus parseProcessingStatus(String status) {
        if (status == null) {
            return ProcessingStatus.PENDING;
        }
        try {
            return ProcessingStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown processing status: {}, defaulting to PENDING", status);
            return ProcessingStatus.PENDING;
        }
    }
}
