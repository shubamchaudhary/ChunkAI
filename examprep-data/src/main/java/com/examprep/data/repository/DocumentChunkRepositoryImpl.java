package com.examprep.data.repository;

import com.examprep.data.entity.DocumentChunk;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class DocumentChunkRepositoryImpl implements DocumentChunkRepositoryCustom {
    
    @PersistenceContext
    private EntityManager entityManager;
    
    @Override
    public List<DocumentChunk> findSimilarChunksCustom(
        UUID userId,
        String queryEmbedding,
        UUID[] documentIds,
        int limit
    ) {
        String sql;
        Query query;
        
        if (documentIds != null && documentIds.length > 0) {
            // Search specific documents - select only IDs to avoid vector mapping issues
            sql = """
                SELECT dc.id FROM document_chunks dc
                JOIN documents d ON dc.document_id = d.id
                WHERE dc.user_id = :userId
                  AND d.processing_status = 'COMPLETED'
                  AND dc.document_id = ANY(CAST(:documentIds AS uuid[]))
                ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
                LIMIT :limit
                """;
            query = entityManager.createNativeQuery(sql);
            query.setParameter("documentIds", documentIds);
        } else {
            // Search all user's documents - select only IDs to avoid vector mapping issues
            sql = """
                SELECT dc.id FROM document_chunks dc
                JOIN documents d ON dc.document_id = d.id
                WHERE dc.user_id = :userId
                  AND d.processing_status = 'COMPLETED'
                ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
                LIMIT :limit
                """;
            query = entityManager.createNativeQuery(sql);
        }
        
        query.setParameter("userId", userId);
        query.setParameter("queryEmbedding", queryEmbedding);
        query.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Object> idResults = query.getResultList();
        
        // Convert to UUIDs and load full entities
        List<UUID> chunkIds = idResults.stream()
            .map(id -> UUID.fromString(id.toString()))
            .collect(Collectors.toList());
        
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        
        // Load entities using native query that excludes embedding column to avoid vector type issues
        // Then load embedding separately if needed (we don't need it for RAG, just content)
        String loadSql = """
            SELECT id, document_id, user_id, chunk_index, content, content_hash, 
                   page_number, slide_number, section_title, token_count, created_at
            FROM document_chunks
            WHERE id = ANY(CAST(:ids AS uuid[]))
            """;
        Query loadQuery = entityManager.createNativeQuery(loadSql);
        loadQuery.setParameter("ids", chunkIds.toArray(new UUID[0]));
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = loadQuery.getResultList();
        
        // Map results to DocumentChunk entities
        Map<UUID, DocumentChunk> chunkMap = new LinkedHashMap<>();
        for (Object[] row : results) {
            UUID id = (UUID) row[0];
            UUID docId = (UUID) row[1];
            UUID chunkUserId = (UUID) row[2];
            Integer chunkIndex = (Integer) row[3];
            String content = (String) row[4];
            String contentHash = (String) row[5];
            Integer pageNumber = row[6] != null ? (Integer) row[6] : null;
            Integer slideNumber = row[7] != null ? (Integer) row[7] : null;
            String sectionTitle = (String) row[8];
            Integer tokenCount = row[9] != null ? (Integer) row[9] : null;
            java.time.Instant createdAt = null;
            if (row[10] != null) {
                if (row[10] instanceof java.time.Instant) {
                    createdAt = (java.time.Instant) row[10];
                } else if (row[10] instanceof java.sql.Timestamp) {
                    createdAt = ((java.sql.Timestamp) row[10]).toInstant();
                } else if (row[10] instanceof java.time.OffsetDateTime) {
                    createdAt = ((java.time.OffsetDateTime) row[10]).toInstant();
                }
            }
            
            DocumentChunk chunk = DocumentChunk.builder()
                .id(id)
                .userId(chunkUserId)
                .chunkIndex(chunkIndex)
                .content(content)
                .contentHash(contentHash)
                .pageNumber(pageNumber)
                .slideNumber(slideNumber)
                .sectionTitle(sectionTitle)
                .tokenCount(tokenCount)
                .createdAt(createdAt)
                .embedding(null) // We don't need embedding for RAG, just content
                .build();
            
            // Load document separately
            chunk.setDocument(entityManager.find(com.examprep.data.entity.Document.class, docId));
            chunkMap.put(id, chunk);
        }
        
        // Return in the order from vector similarity search
        return chunkIds.stream()
            .map(chunkMap::get)
            .filter(chunk -> chunk != null)
            .collect(Collectors.toList());
    }
}

