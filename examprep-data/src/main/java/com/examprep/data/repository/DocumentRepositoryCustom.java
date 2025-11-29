package com.examprep.data.repository;

import com.examprep.data.entity.Document;

import java.util.List;
import java.util.UUID;

public interface DocumentRepositoryCustom {
    /**
     * v2.0: Find similar documents by summary embedding.
     */
    List<Document> findSimilarDocumentsBySummary(
        UUID chatId,
        String queryEmbedding,
        int limit
    );
    
    /**
     * Find documents by chat ID excluding summary_embedding to avoid vector type mapping issues.
     */
    List<Document> findByChatIdExcludingEmbedding(UUID chatId);
    
    /**
     * Find document by ID excluding summary_embedding to avoid vector type mapping issues.
     * Returns Optional.empty() if document not found or if there's an error loading it.
     */
    java.util.Optional<Document> findByIdExcludingEmbedding(UUID documentId);
}

