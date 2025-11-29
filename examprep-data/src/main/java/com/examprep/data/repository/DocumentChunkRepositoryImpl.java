package com.examprep.data.repository;

import com.examprep.common.constants.ProcessingStatus;
import com.examprep.data.entity.DocumentChunk;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class DocumentChunkRepositoryImpl implements DocumentChunkRepositoryCustom {
    
    @PersistenceContext
    private EntityManager entityManager;
    
    @Override
    public List<DocumentChunk> findSimilarChunksCustom(
        UUID userId,
        String queryEmbedding,
        UUID[] documentIds,
        UUID chatId,
        boolean useCrossChat,
        int limit
    ) {
        String sql;
        Query query;
        
        // Build WHERE clause based on search scope
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT dc.id FROM document_chunks dc ");
        sqlBuilder.append("JOIN documents d ON dc.document_id = d.id ");
        sqlBuilder.append("WHERE dc.user_id = :userId ");
        sqlBuilder.append("AND dc.embedding IS NOT NULL ");  // Only search chunks with embeddings (more resilient than checking status)
        
        // Strict filtering: When useCrossChat is false, MUST filter by chatId
        // When useCrossChat is true, search across all user's chats (no chatId filter)
        if (!useCrossChat) {
            if (chatId == null) {
                // If chatId is null and useCrossChat is false, return empty results (no chat specified)
                log.warn("useCrossChat is false but chatId is null - returning empty results");
                return List.of();
            }
            sqlBuilder.append("AND dc.chat_id = :chatId ");
            log.debug("Filtering by chatId: {} (useCrossChat: false)", chatId);
        } else {
            log.debug("Searching across all chats (useCrossChat: true)");
        }
        
        if (documentIds != null && documentIds.length > 0) {
            sqlBuilder.append("AND dc.document_id = ANY(CAST(:documentIds AS uuid[])) ");
        }
        
        sqlBuilder.append("ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector) ");
        sqlBuilder.append("LIMIT :limit");
        
        sql = sqlBuilder.toString();
        query = entityManager.createNativeQuery(sql);
        query.setParameter("userId", userId);
        query.setParameter("queryEmbedding", queryEmbedding);
        query.setParameter("limit", limit);
        
        if (documentIds != null && documentIds.length > 0) {
            query.setParameter("documentIds", documentIds);
        }
        
        if (!useCrossChat && chatId != null) {
            query.setParameter("chatId", chatId);
        }
        
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
        // Add additional chatId filter for safety (double-check)
        StringBuilder loadSqlBuilder = new StringBuilder();
        loadSqlBuilder.append("""
            SELECT id, document_id, user_id, chat_id, chunk_index, content, content_hash, 
                   page_number, slide_number, section_title, token_count, created_at
            FROM document_chunks
            WHERE id = ANY(CAST(:ids AS uuid[]))
            """);
        
        // Add chatId filter if not using cross-chat search (extra safety check)
        if (!useCrossChat && chatId != null) {
            loadSqlBuilder.append(" AND chat_id = :chatId ");
        }
        
        Query loadQuery = entityManager.createNativeQuery(loadSqlBuilder.toString());
        loadQuery.setParameter("ids", chunkIds.toArray(new UUID[0]));
        
        if (!useCrossChat && chatId != null) {
            loadQuery.setParameter("chatId", chatId);
        }
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = loadQuery.getResultList();
        
        // Map results to DocumentChunk entities
        Map<UUID, DocumentChunk> chunkMap = new LinkedHashMap<>();
        for (Object[] row : results) {
            UUID id = (UUID) row[0];
            UUID docId = (UUID) row[1];
            UUID chunkUserId = (UUID) row[2];
            UUID chunkChatId = (UUID) row[3];
            Integer chunkIndex = (Integer) row[4];
            String content = (String) row[5];
            String contentHash = (String) row[6];
            Integer pageNumber = row[7] != null ? (Integer) row[7] : null;
            Integer slideNumber = row[8] != null ? (Integer) row[8] : null;
            String sectionTitle = (String) row[9];
            Integer tokenCount = row[10] != null ? (Integer) row[10] : null;
            java.time.Instant createdAt = null;
            if (row[11] != null) {
                if (row[11] instanceof java.time.Instant) {
                    createdAt = (java.time.Instant) row[11];
                } else if (row[11] instanceof java.sql.Timestamp) {
                    createdAt = ((java.sql.Timestamp) row[11]).toInstant();
                } else if (row[11] instanceof java.time.OffsetDateTime) {
                    createdAt = ((java.time.OffsetDateTime) row[11]).toInstant();
                }
            }
            
            DocumentChunk chunk = DocumentChunk.builder()
                .id(id)
                .userId(chunkUserId)
                .chatId(chunkChatId)
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
            
            // Load document using native query to exclude embedding column (avoids NULL vector issues)
            Query docQuery = entityManager.createNativeQuery(
                """
                SELECT id, user_id, chat_id, file_name, original_file_name,
                       file_type, file_size_bytes, mime_type, total_pages, total_chunks,
                       processing_status, processing_started_at, processing_completed_at,
                       error_message, processing_tier, chunks_embedded, created_at, updated_at
                FROM documents
                WHERE id = :docId
                """
            );
            docQuery.setParameter("docId", docId);
            
            @SuppressWarnings("unchecked")
            List<Object[]> docRows = docQuery.getResultList();
            
            if (!docRows.isEmpty()) {
                try {
                    Object[] docRow = docRows.get(0);
                    com.examprep.data.entity.Document doc = buildDocumentFromRow(docRow);
                    chunk.setDocument(doc);
                } catch (Exception e) {
                    log.warn("Failed to construct document for chunk {}: {}", id, e.getMessage());
                    // Set document reference without loading (will fail if accessed, but avoids immediate error)
                    chunk.setDocument(entityManager.getReference(com.examprep.data.entity.Document.class, docId));
                }
            }
            
            chunkMap.put(id, chunk);
        }
        
        // Return in the order from vector similarity search
        return chunkIds.stream()
            .map(chunkMap::get)
            .filter(chunk -> chunk != null)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<DocumentChunkRepositoryCustom.ScoredChunk> findSimilarChunksInDocuments(
        UUID chatId,
        List<UUID> documentIds,
        String queryEmbedding,
        int limit
    ) {
        // First, get chunk IDs with similarity scores
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT dc.id, ");
        sqlBuilder.append("1 - (dc.embedding <=> CAST(:queryEmbedding AS vector)) AS similarity ");
        sqlBuilder.append("FROM document_chunks dc ");
        sqlBuilder.append("JOIN documents d ON dc.document_id = d.id ");
        sqlBuilder.append("WHERE dc.chat_id = :chatId ");
        sqlBuilder.append("AND dc.embedding IS NOT NULL ");  // Only search chunks with embeddings (more resilient than checking status)
        
        // Only filter by documentIds if provided (otherwise search all documents in chat)
        if (documentIds != null && !documentIds.isEmpty()) {
            sqlBuilder.append("AND dc.document_id = ANY(CAST(:documentIds AS uuid[])) ");
        }
        
        sqlBuilder.append("ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector) ");
        sqlBuilder.append("LIMIT :limit");
        
        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("chatId", chatId);
        query.setParameter("queryEmbedding", queryEmbedding);
        query.setParameter("limit", limit);
        
        // Only set documentIds parameter if provided
        if (documentIds != null && !documentIds.isEmpty()) {
            query.setParameter("documentIds", documentIds.toArray(new UUID[0]));
        }
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        // Extract IDs and scores
        Map<UUID, Double> idToScore = new LinkedHashMap<>();
        for (Object[] row : results) {
            UUID id = (UUID) row[0];
            Double similarity = ((Number) row[1]).doubleValue();
            idToScore.put(id, similarity);
        }
        
        if (idToScore.isEmpty()) {
            return List.of();
        }
        
        // Load chunks (without embedding)
        StringBuilder loadSql = new StringBuilder();
        loadSql.append("""
            SELECT id, document_id, user_id, chat_id, chunk_index, content, content_hash,
                   page_number, slide_number, section_title, token_count, chunk_type, key_terms, created_at
            FROM document_chunks
            WHERE id = ANY(CAST(:ids AS uuid[]))
            """);
        
        Query loadQuery = entityManager.createNativeQuery(loadSql.toString());
        loadQuery.setParameter("ids", idToScore.keySet().toArray(new UUID[0]));
        
        @SuppressWarnings("unchecked")
        List<Object[]> chunkRows = loadQuery.getResultList();
        
        // Map to ScoredChunk objects
        Map<UUID, DocumentChunk> chunkMap = new LinkedHashMap<>();
        for (Object[] row : chunkRows) {
            UUID id = (UUID) row[0];
            UUID docId = (UUID) row[1];
            UUID userId = (UUID) row[2];
            UUID chunkChatId = (UUID) row[3];
            Integer chunkIndex = (Integer) row[4];
            String content = (String) row[5];
            String contentHash = (String) row[6];
            Integer pageNumber = row[7] != null ? (Integer) row[7] : null;
            Integer slideNumber = row[8] != null ? (Integer) row[8] : null;
            String sectionTitle = (String) row[9];
            Integer tokenCount = row[10] != null ? (Integer) row[10] : null;
            String chunkType = (String) row[11];
            String[] keyTerms = null;
            if (row[12] != null) {
                try {
                    if (row[12] instanceof String[]) {
                        // PostgreSQL sometimes returns String[] directly
                        keyTerms = (String[]) row[12];
                    } else if (row[12] instanceof java.sql.Array) {
                        // PostgreSQL sometimes returns java.sql.Array
                        java.sql.Array array = (java.sql.Array) row[12];
                        Object[] arrayData = (Object[]) array.getArray();
                        if (arrayData != null) {
                            keyTerms = Arrays.stream(arrayData)
                                .map(String::valueOf)
                                .toArray(String[]::new);
                        }
                    } else if (row[12] instanceof Object[]) {
                        // Handle generic Object[] case
                        Object[] arrayData = (Object[]) row[12];
                        keyTerms = Arrays.stream(arrayData)
                            .map(String::valueOf)
                            .toArray(String[]::new);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse key_terms array", e);
                }
            }
            java.time.Instant createdAt = null;
            if (row[13] != null) {
                if (row[13] instanceof java.time.Instant) {
                    createdAt = (java.time.Instant) row[13];
                } else if (row[13] instanceof java.sql.Timestamp) {
                    createdAt = ((java.sql.Timestamp) row[13]).toInstant();
                } else if (row[13] instanceof java.time.OffsetDateTime) {
                    createdAt = ((java.time.OffsetDateTime) row[13]).toInstant();
                }
            }
            
            DocumentChunk chunk = DocumentChunk.builder()
                .id(id)
                .userId(userId)
                .chatId(chunkChatId)
                .chunkIndex(chunkIndex)
                .content(content)
                .contentHash(contentHash)
                .pageNumber(pageNumber)
                .slideNumber(slideNumber)
                .sectionTitle(sectionTitle)
                .tokenCount(tokenCount)
                .chunkType(chunkType)
                .keyTerms(keyTerms)
                .createdAt(createdAt)
                .build();
            
            // Load document using native query to exclude embedding column (avoids NULL vector issues)
            Query docQuery = entityManager.createNativeQuery(
                """
                SELECT id, user_id, chat_id, file_name, original_file_name,
                       file_type, file_size_bytes, mime_type, total_pages, total_chunks,
                       processing_status, processing_started_at, processing_completed_at,
                       error_message, processing_tier, chunks_embedded, created_at, updated_at
                FROM documents
                WHERE id = :docId
                """
            );
            docQuery.setParameter("docId", docId);
            
            @SuppressWarnings("unchecked")
            List<Object[]> docRows = docQuery.getResultList();
            
            if (!docRows.isEmpty()) {
                try {
                    Object[] docRow = docRows.get(0);
                    com.examprep.data.entity.Document doc = buildDocumentFromRow(docRow);
                    chunk.setDocument(doc);
                } catch (Exception e) {
                    log.warn("Failed to construct document for chunk {}: {}", id, e.getMessage());
                    // Set document reference without loading (will fail if accessed, but avoids immediate error)
                    chunk.setDocument(entityManager.getReference(com.examprep.data.entity.Document.class, docId));
                }
            }
            
            chunkMap.put(id, chunk);
        }
        
        // Return ScoredChunks in order
        return idToScore.entrySet().stream()
            .map(entry -> new DocumentChunkRepositoryCustom.ScoredChunk(
                chunkMap.get(entry.getKey()),
                entry.getValue()
            ))
            .filter(sc -> sc.getChunk() != null)
            .collect(Collectors.toList());
    }
    
    /**
     * Helper method to build Document entity from native query row (updated schema without summary fields).
     */
    private com.examprep.data.entity.Document buildDocumentFromRow(Object[] row) {
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
        String processingTier = row.length > 14 ? (String) row[14] : null;
        Integer chunksEmbedded = row.length > 15 && row[15] != null ? ((Number) row[15]).intValue() : null;
        java.time.Instant createdAt = convertToInstant(row[16]);
        java.time.Instant updatedAt = convertToInstant(row[17]);
        
        com.examprep.data.entity.Document doc = com.examprep.data.entity.Document.builder()
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
        
        // Set lazy-loaded relationships using references (avoids loading them)
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
    
    /**
     * Batch embedding: Find chunks by keyword search using full-text search (tsvector).
     * Returns chunks ordered by relevance rank.
     */
    @Override
    public List<ScoredChunk> findChunksByKeywordSearch(
        UUID chatId,
        List<UUID> documentIds,
        String searchQuery,
        int limit
    ) {
        String sql;
        Query query;
        
        // Build WHERE clause
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("""
            SELECT dc.id, dc.document_id, dc.user_id, dc.chat_id, dc.chunk_index, 
                   dc.content, dc.content_hash, dc.page_number, dc.slide_number, 
                   dc.section_title, dc.token_count, dc.created_at, dc.chunk_type, dc.key_terms,
                   ts_rank(dc.content_tsv, plainto_tsquery('english', :searchQuery)) as keyword_rank
            FROM document_chunks dc
            WHERE dc.chat_id = :chatId
              AND dc.content_tsv @@ plainto_tsquery('english', :searchQuery)
            """);
        
        // Filter by document IDs if provided
        if (documentIds != null && !documentIds.isEmpty()) {
            sqlBuilder.append(" AND dc.document_id IN (");
            for (int i = 0; i < documentIds.size(); i++) {
                if (i > 0) sqlBuilder.append(", ");
                sqlBuilder.append(":docId").append(i);
            }
            sqlBuilder.append(") ");
        }
        
        sqlBuilder.append(" ORDER BY keyword_rank DESC LIMIT :limit");
        
        sql = sqlBuilder.toString();
        query = entityManager.createNativeQuery(sql);
        query.setParameter("chatId", chatId);
        query.setParameter("searchQuery", searchQuery);
        query.setParameter("limit", limit);
        
        // Set document ID parameters if provided
        if (documentIds != null && !documentIds.isEmpty()) {
            for (int i = 0; i < documentIds.size(); i++) {
                query.setParameter("docId" + i, documentIds.get(i));
            }
        }
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        Map<UUID, DocumentChunk> chunkMap = new LinkedHashMap<>();
        Map<UUID, Double> idToScore = new LinkedHashMap<>();
        
        for (Object[] row : results) {
            UUID id = (UUID) row[0];
            UUID docId = (UUID) row[1];
            UUID userId = (UUID) row[2];
            UUID chunkChatId = (UUID) row[3];
            Integer chunkIndex = (Integer) row[4];
            String content = (String) row[5];
            String contentHash = (String) row[6];
            Integer pageNumber = row[7] != null ? ((Number) row[7]).intValue() : null;
            Integer slideNumber = row[8] != null ? ((Number) row[8]).intValue() : null;
            String sectionTitle = (String) row[9];
            Integer tokenCount = row[10] != null ? ((Number) row[10]).intValue() : null;
            java.time.Instant createdAt = convertToInstant(row[11]);
            String chunkType = (String) row[12];
            String[] keyTerms = parseKeyTerms(row[13]);
            Double keywordRank = row[14] != null ? ((Number) row[14]).doubleValue() : 0.0;
            
            DocumentChunk chunk = DocumentChunk.builder()
                .id(id)
                .userId(userId)
                .chatId(chunkChatId)
                .chunkIndex(chunkIndex)
                .content(content)
                .contentHash(contentHash)
                .pageNumber(pageNumber)
                .slideNumber(slideNumber)
                .sectionTitle(sectionTitle)
                .tokenCount(tokenCount)
                .createdAt(createdAt)
                .chunkType(chunkType)
                .keyTerms(keyTerms)
                .embedding(null) // Not needed for keyword search
                .build();
            
            // Load document using buildDocumentFromRow
            try {
                Query docQuery = entityManager.createNativeQuery("""
                    SELECT id, user_id, chat_id, file_name, original_file_name,
                           file_type, file_size_bytes, mime_type, total_pages, total_chunks,
                           processing_status, processing_started_at, processing_completed_at,
                           error_message, processing_tier, chunks_embedded, created_at, updated_at
                    FROM documents
                    WHERE id = :docId
                    """
                );
                docQuery.setParameter("docId", docId);
                
                @SuppressWarnings("unchecked")
                List<Object[]> docRows = docQuery.getResultList();
                
                if (!docRows.isEmpty()) {
                    com.examprep.data.entity.Document doc = buildDocumentFromRowForChunks(docRows.get(0));
                    chunk.setDocument(doc);
                }
            } catch (Exception e) {
                log.warn("Failed to load document for chunk {}, using reference", id, e);
                chunk.setDocument(entityManager.getReference(com.examprep.data.entity.Document.class, docId));
            }
            
            chunkMap.put(id, chunk);
            idToScore.put(id, keywordRank);
        }
        
        // Return ScoredChunks ordered by keyword rank
        return idToScore.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .limit(limit)
            .map(entry -> new ScoredChunk(chunkMap.get(entry.getKey()), entry.getValue()))
            .filter(sc -> sc.getChunk() != null)
            .collect(Collectors.toList());
    }
    
    /**
     * Helper method to parse key_terms array.
     */
    private String[] parseKeyTerms(Object rawKeyTerms) {
        if (rawKeyTerms == null) {
            return null;
        }
        try {
            if (rawKeyTerms instanceof String[]) {
                return (String[]) rawKeyTerms;
            } else if (rawKeyTerms instanceof java.sql.Array) {
                java.sql.Array array = (java.sql.Array) rawKeyTerms;
                Object[] arrayData = (Object[]) array.getArray();
                if (arrayData != null) {
                    return Arrays.stream(arrayData)
                        .map(String::valueOf)
                        .toArray(String[]::new);
                }
            } else if (rawKeyTerms instanceof Object[]) {
                Object[] arrayData = (Object[]) rawKeyTerms;
                return Arrays.stream(arrayData)
                    .map(String::valueOf)
                    .toArray(String[]::new);
            }
        } catch (Exception e) {
            log.debug("Failed to parse key_terms array", e);
        }
        return null;
    }
    
    /**
     * Helper method to build Document entity from native query row (updated for new schema without summaries).
     */
    private com.examprep.data.entity.Document buildDocumentFromRowForChunks(Object[] row) {
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
        String processingTier = row.length > 14 ? (String) row[14] : null;
        Integer chunksEmbedded = row.length > 15 && row[15] != null ? ((Number) row[15]).intValue() : null;
        java.time.Instant createdAt = convertToInstant(row[16]);
        java.time.Instant updatedAt = convertToInstant(row[17]);
        
        com.examprep.data.entity.Document doc = com.examprep.data.entity.Document.builder()
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
            .processingTier(processingTier)
            .chunksEmbedded(chunksEmbedded != null ? chunksEmbedded : 0)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build();
        
        doc.setUser(entityManager.getReference(com.examprep.data.entity.User.class, userId));
        doc.setChat(entityManager.getReference(com.examprep.data.entity.Chat.class, chatIdFromRow));
        
        return doc;
    }
}

