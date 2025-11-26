package com.examprep.data.repository;

import com.examprep.data.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
    
    List<DocumentChunk> findByDocumentId(UUID documentId);
    
    void deleteByDocumentId(UUID documentId);
    
    long countByUserId(UUID userId);
    
    /**
     * Vector similarity search using pgvector.
     * Uses cosine distance (<=>) operator.
     */
    @Query(value = """
        SELECT dc.* FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE dc.user_id = :userId
          AND d.processing_status = 'COMPLETED'
          AND (:documentIds IS NULL OR dc.document_id = ANY(CAST(:documentIds AS uuid[])))
        ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findSimilarChunks(
        UUID userId,
        String queryEmbedding,
        UUID[] documentIds,
        int limit
    );
}

