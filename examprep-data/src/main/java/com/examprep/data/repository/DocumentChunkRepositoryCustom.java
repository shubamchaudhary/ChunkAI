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
}

