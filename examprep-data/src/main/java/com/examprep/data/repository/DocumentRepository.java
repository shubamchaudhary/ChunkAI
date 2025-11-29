package com.examprep.data.repository;

import com.examprep.common.constants.ProcessingStatus;
import com.examprep.data.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID>, DocumentRepositoryCustom {
    
    Page<Document> findByUserId(UUID userId, Pageable pageable);
    
    Page<Document> findByUserIdAndProcessingStatus(
        UUID userId, 
        ProcessingStatus status, 
        Pageable pageable
    );
    
    Optional<Document> findByIdAndUserId(UUID id, UUID userId);
    
    List<Document> findByUserIdAndProcessingStatusIn(
        UUID userId, 
        List<ProcessingStatus> statuses
    );
    
    @Query("""
        SELECT SUM(d.fileSizeBytes) 
        FROM Document d 
        WHERE d.user.id = :userId
    """)
    Long getTotalStorageByUserId(UUID userId);
    
    long countByUserId(UUID userId);
    
    /**
     * Check if a document with the same original filename and file size exists for the user.
     * Used for duplicate detection.
     */
    Optional<Document> findByUserIdAndOriginalFileNameAndFileSizeBytes(
        UUID userId,
        String originalFileName,
        Long fileSizeBytes
    );
    
    // Chat-scoped queries
    Page<Document> findByChatId(UUID chatId, Pageable pageable);
    
    List<Document> findByChatId(UUID chatId);
    
    Optional<Document> findByIdAndChatId(UUID id, UUID chatId);
    
    /**
     * Find document by ID, chat ID, and user ID (for security verification)
     */
    @Query("SELECT d FROM Document d WHERE d.id = :id AND d.chat.id = :chatId AND d.user.id = :userId")
    Optional<Document> findByIdAndChatIdAndUserId(
        @Param("id") UUID id,
        @Param("chatId") UUID chatId,
        @Param("userId") UUID userId
    );
    
    /**
     * Check if a document with the same original filename and file size exists in a chat.
     * Used for duplicate detection within a chat.
     */
    Optional<Document> findByChatIdAndOriginalFileNameAndFileSizeBytes(
        UUID chatId,
        String originalFileName,
        Long fileSizeBytes
    );
    
    long countByChatId(UUID chatId);
    
    // Use native query to delete documents without loading related entities
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM documents WHERE chat_id = :chatId", nativeQuery = true)
    void deleteByChatId(@Param("chatId") UUID chatId);
    
    /**
     * Update document processing status using native query to avoid loading embedding column.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE documents 
        SET processing_status = :status,
            processing_started_at = :startedAt,
            processing_completed_at = :completedAt,
            error_message = :errorMessage,
            total_pages = COALESCE(:totalPages, total_pages),
            total_chunks = COALESCE(:totalChunks, total_chunks)
        WHERE id = :id
        """, nativeQuery = true)
    void updateProcessingStatus(
        @Param("id") UUID id,
        @Param("status") String status,
        @Param("startedAt") java.time.Instant startedAt,
        @Param("completedAt") java.time.Instant completedAt,
        @Param("errorMessage") String errorMessage,
        @Param("totalPages") Integer totalPages,
        @Param("totalChunks") Integer totalChunks
    );
    
    /**
     * Update document progress for batch embedding (processing tier, chunks_embedded, total_chunks).
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE documents 
        SET processing_tier = :processingTier,
            chunks_embedded = :chunksEmbedded,
            total_chunks = :totalChunks
        WHERE id = :id
        """, nativeQuery = true)
    void updateDocumentProgress(
        @Param("id") UUID id,
        @Param("processingTier") String processingTier,
        @Param("chunksEmbedded") Integer chunksEmbedded,
        @Param("totalChunks") Integer totalChunks
    );
    
}

