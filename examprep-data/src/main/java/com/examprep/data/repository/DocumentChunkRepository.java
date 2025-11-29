package com.examprep.data.repository;

import com.examprep.data.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID>, DocumentChunkRepositoryCustom {
    
    List<DocumentChunk> findByDocument_Id(UUID documentId);
    
    // Use native query to delete without loading vector embeddings
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM document_chunks WHERE document_id = :documentId", nativeQuery = true)
    void deleteByDocumentId(@Param("documentId") UUID documentId);
    
    // Use native query to delete chunks by chat without loading vector embeddings
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM document_chunks WHERE chat_id = :chatId", nativeQuery = true)
    void deleteByChatId(@Param("chatId") UUID chatId);
    
    long countByUserId(UUID userId);
    
    /**
     * Find chunks without embeddings for batch processing.
     * Used by BatchEmbeddingJob to find chunks that need embeddings.
     */
    @Query(value = """
        SELECT dc.* FROM document_chunks dc
        WHERE dc.embedding IS NULL
        ORDER BY dc.document_id, dc.chunk_index
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findChunksWithoutEmbeddings(@Param("limit") int limit);
    
    /**
     * Count chunks without embeddings for a specific document.
     */
    @Query(value = """
        SELECT COUNT(*) FROM document_chunks
        WHERE document_id = :documentId AND embedding IS NULL
        """, nativeQuery = true)
    long countChunksWithoutEmbeddingsByDocumentId(@Param("documentId") UUID documentId);
    
    /**
     * Count total chunks for a specific document.
     * Uses native query to avoid loading entities with embeddings.
     */
    @Query(value = """
        SELECT COUNT(*) FROM document_chunks
        WHERE document_id = :documentId
        """, nativeQuery = true)
    long countByDocumentId(@Param("documentId") UUID documentId);
    
    /**
     * Update chunk embedding using native query to avoid loading the vector.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE document_chunks 
        SET embedding = CAST(:embedding AS vector)
        WHERE id = :chunkId
        """, nativeQuery = true)
    void updateChunkEmbedding(@Param("chunkId") UUID chunkId, @Param("embedding") String embedding);
}

