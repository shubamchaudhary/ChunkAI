package com.examprep.core.cache;

import com.examprep.core.query.model.QueryResult;
import com.examprep.data.entity.QueryCache;
import com.examprep.data.repository.ChatRepository;
import com.examprep.data.repository.QueryCacheRepository;
import com.examprep.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryCacheService {
    
    private final QueryCacheRepository cacheRepository;
    private final EmbeddingService embeddingService;
    private final ChatRepository chatRepository;
    
    private static final double SEMANTIC_SIMILARITY_THRESHOLD = 0.92;
    private static final int CACHE_HOURS = 24;
    
    /**
     * Find cached answer for a query.
     * Uses two-level matching:
     * 1. Exact match (by hash)
     * 2. Semantic match (by embedding similarity)
     */
    public Optional<QueryResult> findCachedAnswer(UUID chatId, String question) {
        long startTime = System.currentTimeMillis();
        
        log.debug("[CACHE] Checking cache for query | chatId={} | questionLength={}", 
            chatId, question.length());
        
        // Clean expired cache first
        long cleanupStartTime = System.currentTimeMillis();
        cleanExpiredCache();
        long cleanupDuration = System.currentTimeMillis() - cleanupStartTime;
        
        if (cleanupDuration > 100) {
            log.debug("[CACHE] Cleanup took longer than expected | cleanupDurationMs={}", cleanupDuration);
        }
        
        // Level 1: Exact match
        long exactMatchStartTime = System.currentTimeMillis();
        String queryHash = hashQuery(normalizeQuery(question));
        // Use native query to get cache data without loading embedding field
        List<Object[]> cacheDataList = cacheRepository.findCacheDataByChatIdAndQueryHash(chatId, queryHash);
        long exactMatchDuration = System.currentTimeMillis() - exactMatchStartTime;
        
        if (!cacheDataList.isEmpty()) {
            Object[] row = cacheDataList.get(0);
            UUID cacheId = (UUID) row[0];
            String responseText = (String) row[1];
            String sourcesUsed = (String) row[2];
            Integer hitCount = ((Number) row[3]).intValue();
            
            // Increment hit count
            cacheRepository.incrementHitCount(cacheId);
            
            long totalDuration = System.currentTimeMillis() - startTime;
            
            log.info("[CACHE] Exact cache hit | chatId={} | queryHash={} | hitCount={} | totalDurationMs={} | cacheDuration={}", 
                chatId, queryHash.substring(0, Math.min(8, queryHash.length())), 
                hitCount, totalDuration, exactMatchDuration);
            
            // Build QueryResult directly from cache data
            QueryResult result = QueryResult.builder()
                .answer(responseText)
                .sources(parseSources(sourcesUsed))
                .chunksUsed(0) // Not stored in cache
                .llmCallsUsed(0) // Cache hit = 0 calls
                .processingMode("cached")
                .cacheHit(true)
                .build();
            
            return Optional.of(result);
        }
        
        log.debug("[CACHE] Exact cache miss | chatId={} | exactMatchDurationMs={}", chatId, exactMatchDuration);
        
        // Level 2: Semantic match
        long semanticStartTime = System.currentTimeMillis();
        float[] queryEmbedding = embeddingService.generateEmbedding(question);
        String embeddingString = embeddingService.toVectorString(queryEmbedding);
        long embeddingDuration = System.currentTimeMillis() - semanticStartTime;
        
        log.debug("[CACHE] Query embedding generated for semantic search | chatId={} | embeddingDurationMs={}", 
            chatId, embeddingDuration);
        
        long semanticSearchStartTime = System.currentTimeMillis();
        List<Object[]> similarQueries = cacheRepository.findSimilarQueriesNative(
            chatId,
            embeddingString,
            SEMANTIC_SIMILARITY_THRESHOLD,
            5  // Check top 5 similar queries
        );
        long semanticSearchDuration = System.currentTimeMillis() - semanticSearchStartTime;
        
        if (!similarQueries.isEmpty()) {
            // Parse result (id, similarity score)
            UUID bestMatchId = (UUID) similarQueries.get(0)[0];
            Double similarity = ((Number) similarQueries.get(0)[similarQueries.get(0).length - 1]).doubleValue();
            
            QueryCache bestMatch = cacheRepository.findById(bestMatchId).orElse(null);
            if (bestMatch != null) {
                updateHitCount(bestMatch);
                long totalDuration = System.currentTimeMillis() - startTime;
                
                log.info("[CACHE] Semantic cache hit | chatId={} | similarity={} | hitCount={} | totalDurationMs={} | semanticSearchDurationMs={}", 
                    chatId, similarity, bestMatch.getHitCount(), totalDuration, semanticSearchDuration);
                
                QueryResult result = toQueryResult(bestMatch);
                return Optional.of(result);
            }
        }
        
        long totalDuration = System.currentTimeMillis() - startTime;
        log.debug("[CACHE] Cache miss (both exact and semantic) | chatId={} | totalDurationMs={}", 
            chatId, totalDuration);
        
        return Optional.empty();
    }
    
    /**
     * Cache a query result.
     */
    public void cacheResult(UUID chatId, String question, QueryResult result) {
        try {
            String normalizedQuery = normalizeQuery(question);
            String queryHash = hashQuery(normalizedQuery);
            float[] queryEmbedding = embeddingService.generateEmbedding(question);
            
            com.examprep.data.entity.Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
            
            QueryCache cache = QueryCache.builder()
                .chat(chat)
                .user(chat.getUser())
                .queryText(question)
                .queryHash(queryHash)
                .queryEmbedding(queryEmbedding)
                .responseText(result.getAnswer())
                .sourcesUsed(convertSourcesToJson(result.getSources()))
                .expiresAt(Instant.now().plus(CACHE_HOURS, ChronoUnit.HOURS))
                .hitCount(0)
                .build();
            
            cacheRepository.save(cache);
            log.debug("Cached query result");
        } catch (Exception e) {
            // Duplicate key or other error - ignore
            log.debug("Failed to cache result: {}", e.getMessage());
        }
    }
    
    /**
     * Invalidate cache for a chat (e.g., when documents are added/removed).
     */
    public void invalidateCache(UUID chatId) {
        cacheRepository.deleteByChatId(chatId);
        log.info("Invalidated cache for chat {}", chatId);
    }
    
    private String normalizeQuery(String query) {
        return query.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", "")
            .replaceAll("\\s+", " ")
            .trim();
    }
    
    private String hashQuery(String normalizedQuery) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalizedQuery.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return String.valueOf(normalizedQuery.hashCode());
        }
    }
    
    private void updateHitCount(QueryCache cache) {
        cacheRepository.incrementHitCount(cache.getId());
    }
    
    private void cleanExpiredCache() {
        cacheRepository.deleteExpired(Instant.now());
    }
    
    private QueryResult toQueryResult(QueryCache cache) {
        return QueryResult.builder()
            .answer(cache.getResponseText())
            .sources(parseSources(cache.getSourcesUsed()))
            .chunksUsed(0) // Not stored in cache
            .llmCallsUsed(0) // Cache hit = 0 calls
            .processingMode("cached")
            .cacheHit(true)
            .build();
    }
    
    private String convertSourcesToJson(List<QueryResult.Source> sources) {
        // Simple JSON conversion (in production, use Jackson)
        if (sources == null || sources.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sources.size(); i++) {
            QueryResult.Source source = sources.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"documentId\":\"").append(source.getDocumentId()).append("\",");
            json.append("\"fileName\":\"").append(source.getFileName()).append("\",");
            if (source.getPageNumber() != null) {
                json.append("\"pageNumber\":").append(source.getPageNumber()).append(",");
            }
            if (source.getSlideNumber() != null) {
                json.append("\"slideNumber\":").append(source.getSlideNumber()).append(",");
            }
            json.append("}");
        }
        json.append("]");
        return json.toString();
    }
    
    private List<QueryResult.Source> parseSources(String sourcesJson) {
        // Simple JSON parsing (in production, use Jackson)
        if (sourcesJson == null || sourcesJson.trim().isEmpty() || sourcesJson.equals("[]")) {
            return List.of();
        }
        // For now, return empty list - proper parsing would require Jackson
        return List.of();
    }
}

