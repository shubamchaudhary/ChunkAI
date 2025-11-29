package com.examprep.data.repository;

import com.examprep.data.entity.DocumentChunk;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepositoryCustom {
    /**
     * Find similar chunks with chat scope support.
     * @param userId User ID
     * @param queryEmbedding Query embedding vector string
     * @param documentIds Optional document IDs to filter by
     * @param chatId Chat ID for chat-scoped search (null for cross-chat search)
     * @param useCrossChat If true, search across all user's chats (ignores chatId)
     * @param limit Maximum number of results
     * @return List of similar chunks
     */
    List<DocumentChunk> findSimilarChunksCustom(
        UUID userId,
        String queryEmbedding,
        UUID[] documentIds,
        UUID chatId,
        boolean useCrossChat,
        int limit
    );
    
    /**
     * v2.0: Find similar chunks within specific documents with similarity scores.
     * Returns chunks ordered by similarity, with scores attached.
     */
    List<ScoredChunk> findSimilarChunksInDocuments(
        UUID chatId,
        List<UUID> documentIds,
        String queryEmbedding,
        int limit
    );
    
    /**
     * Batch embedding: Find chunks by keyword search using full-text search (tsvector).
     * Returns chunks ordered by relevance rank.
     */
    List<ScoredChunk> findChunksByKeywordSearch(
        UUID chatId,
        List<UUID> documentIds,
        String searchQuery,
        int limit
    );
    
    /**
     * Helper class for chunks with similarity scores.
     */
    class ScoredChunk {
        private final DocumentChunk chunk;
        private final double score;
        
        public ScoredChunk(DocumentChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
        
        public DocumentChunk getChunk() { return chunk; }
        public double getScore() { return score; }
        
        // Convenience methods
        public String getContent() { return chunk.getContent(); }
        public UUID getDocumentId() { return chunk.getDocumentId(); }
        public String getFileName() { return chunk.getFileName(); }
        public Integer getPageNumber() { return chunk.getPageNumber(); }
        public Integer getSlideNumber() { return chunk.getSlideNumber(); }
        public String getSectionTitle() { return chunk.getSectionTitle(); }
        public Integer getTokenCount() { return chunk.getTokenCount(); }
        
        public ScoredChunk withScore(double newScore) {
            return new ScoredChunk(chunk, newScore);
        }
    }
}

