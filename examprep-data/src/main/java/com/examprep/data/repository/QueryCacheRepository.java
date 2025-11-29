package com.examprep.data.repository;

import com.examprep.data.entity.QueryCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QueryCacheRepository extends JpaRepository<QueryCache, UUID> {
    
    /**
     * Find cached query by exact hash match.
     * Note: This loads the full entity including embedding, which may fail if embedding is NULL.
     * Use findByChatIdAndQueryHashNative for safer lookup.
     */
    Optional<QueryCache> findByChatIdAndQueryHash(UUID chatId, String queryHash);
    
    /**
     * Find cached query by exact hash match using native query.
     * Returns only the ID to avoid loading embedding field.
     */
    @Query(value = """
        SELECT id FROM query_cache
        WHERE chat_id = :chatId AND query_hash = :queryHash AND expires_at > NOW()
        LIMIT 1
        """, nativeQuery = true)
    Optional<UUID> findIdByChatIdAndQueryHash(
        @Param("chatId") UUID chatId,
        @Param("queryHash") String queryHash
    );
    
    /**
     * Find cached query data by exact hash match using native query.
     * Returns only the fields needed for cache response (excluding embedding).
     */
    @Query(value = """
        SELECT id, response_text, sources_used, hit_count
        FROM query_cache
        WHERE chat_id = :chatId AND query_hash = :queryHash AND expires_at > NOW()
        LIMIT 1
        """, nativeQuery = true)
    List<Object[]> findCacheDataByChatIdAndQueryHash(
        @Param("chatId") UUID chatId,
        @Param("queryHash") String queryHash
    );
    
    /**
     * Find similar queries using semantic similarity (vector search).
     * Returns queries with similarity >= threshold, ordered by similarity DESC.
     */
    @Query(value = """
        SELECT qc.*, 
               1 - (qc.query_embedding <=> CAST(:queryEmbedding AS vector)) AS similarity
        FROM query_cache qc
        WHERE qc.chat_id = :chatId
          AND qc.expires_at > NOW()
          AND 1 - (qc.query_embedding <=> CAST(:queryEmbedding AS vector)) >= :threshold
        ORDER BY qc.query_embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findSimilarQueriesNative(
        @Param("chatId") UUID chatId,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );
    
    /**
     * Delete expired cache entries.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QueryCache qc WHERE qc.expiresAt < :now")
    void deleteExpired(@Param("now") Instant now);
    
    /**
     * Delete all cache entries for a chat.
     */
    @Modifying
    @Transactional
    void deleteByChatId(UUID chatId);
    
    /**
     * Increment hit count for a cache entry.
     */
    @Modifying
    @Transactional
    @Query("UPDATE QueryCache qc SET qc.hitCount = qc.hitCount + 1 WHERE qc.id = :id")
    void incrementHitCount(@Param("id") UUID id);
}

