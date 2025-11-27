package com.examprep.data.repository;

import com.examprep.data.entity.DocumentChunk;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepositoryCustom {
    List<DocumentChunk> findSimilarChunksCustom(
        UUID userId,
        String queryEmbedding,
        UUID[] documentIds,
        int limit
    );
}

