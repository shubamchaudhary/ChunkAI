package com.examprep.core.query;

import com.examprep.data.repository.DocumentChunkRepository;
import com.examprep.data.repository.DocumentChunkRepositoryCustom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid search service that combines keyword search and vector search.
 * Uses Reciprocal Rank Fusion (RRF) to combine results.
 * Gracefully degrades to keyword-only if embeddings are not available yet.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridSearchService {
    
    private final DocumentChunkRepository chunkRepository;
    
    private static final double RRF_K = 60.0; // RRF constant
    
    /**
     * Perform hybrid search: keyword + vector search combined with RRF.
     * 
     * @param chatId Chat ID for scoping
     * @param documentIds Optional document IDs to filter by
     * @param searchQuery User's search query
     * @param queryEmbedding Optional query embedding (if null, will skip vector search)
     * @param limit Maximum number of results
     * @return List of scored chunks ordered by RRF score
     */
    public List<DocumentChunkRepositoryCustom.ScoredChunk> hybridSearch(
        UUID chatId,
        List<UUID> documentIds,
        String searchQuery,
        String queryEmbedding,
        int limit
    ) {
        long startTime = System.currentTimeMillis();
        String searchId = "hybrid-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        
        log.info("[HYBRID_SEARCH] Starting hybrid search | searchId={} | chatId={} | queryLength={} | documentIdsCount={} | hasQueryEmbedding={}", 
            searchId, chatId, searchQuery.length(), 
            documentIds != null ? documentIds.size() : 0, queryEmbedding != null);
        
        // Step 1: Keyword search (always available)
        long keywordStartTime = System.currentTimeMillis();
        List<DocumentChunkRepositoryCustom.ScoredChunk> keywordResults = 
            performKeywordSearch(chatId, documentIds, searchQuery, limit * 2);
        long keywordDuration = System.currentTimeMillis() - keywordStartTime;
        
        log.info("[HYBRID_SEARCH] Keyword search completed | searchId={} | resultsCount={} | durationMs={}", 
            searchId, keywordResults.size(), keywordDuration);
        
        // Step 2: Vector search (only if embeddings exist and query embedding provided)
        List<DocumentChunkRepositoryCustom.ScoredChunk> vectorResults = new ArrayList<>();
        long vectorDuration = 0;
        
        if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
            long vectorStartTime = System.currentTimeMillis();
            try {
                vectorResults = performVectorSearch(chatId, documentIds, queryEmbedding, limit * 2);
                vectorDuration = System.currentTimeMillis() - vectorStartTime;
                
                log.info("[HYBRID_SEARCH] Vector search completed | searchId={} | resultsCount={} | durationMs={}", 
                    searchId, vectorResults.size(), vectorDuration);
            } catch (Exception e) {
                log.warn("[HYBRID_SEARCH] Vector search failed, using keyword-only | searchId={} | error={}", 
                    searchId, e.getMessage());
                vectorDuration = System.currentTimeMillis() - vectorStartTime;
            }
        } else {
            log.debug("[HYBRID_SEARCH] Skipping vector search - no query embedding provided | searchId={}", searchId);
        }
        
        // Step 3: Combine results using Reciprocal Rank Fusion (RRF)
        long combineStartTime = System.currentTimeMillis();
        List<DocumentChunkRepositoryCustom.ScoredChunk> combinedResults = 
            combineWithRRF(keywordResults, vectorResults, limit);
        long combineDuration = System.currentTimeMillis() - combineStartTime;
        
        long totalDuration = System.currentTimeMillis() - startTime;
        
        log.info("[HYBRID_SEARCH] Hybrid search completed | searchId={} | keywordResults={} | vectorResults={} | finalResults={} | totalDurationMs={} | keyword={} | vector={} | combine={}", 
            searchId, keywordResults.size(), vectorResults.size(), combinedResults.size(), totalDuration,
            keywordDuration, vectorDuration, combineDuration);
        
        return combinedResults;
    }
    
    /**
     * Perform keyword search using PostgreSQL full-text search (tsvector).
     */
    private List<DocumentChunkRepositoryCustom.ScoredChunk> performKeywordSearch(
        UUID chatId,
        List<UUID> documentIds,
        String searchQuery,
        int limit
    ) {
        try {
            return chunkRepository.findChunksByKeywordSearch(chatId, documentIds, searchQuery, limit);
        } catch (Exception e) {
            log.error("[HYBRID_SEARCH] Keyword search failed | error={}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Perform vector search (only chunks with embeddings).
     */
    private List<DocumentChunkRepositoryCustom.ScoredChunk> performVectorSearch(
        UUID chatId,
        List<UUID> documentIds,
        String queryEmbedding,
        int limit
    ) {
        try {
            return chunkRepository.findSimilarChunksInDocuments(chatId, documentIds, queryEmbedding, limit);
        } catch (Exception e) {
            log.error("[HYBRID_SEARCH] Vector search failed | error={}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Combine keyword and vector results using Reciprocal Rank Fusion (RRF).
     * RRF_score = 1/(k + keyword_rank) + 1/(k + vector_rank)
     */
    private List<DocumentChunkRepositoryCustom.ScoredChunk> combineWithRRF(
        List<DocumentChunkRepositoryCustom.ScoredChunk> keywordResults,
        List<DocumentChunkRepositoryCustom.ScoredChunk> vectorResults,
        int limit
    ) {
        // Map chunks by ID for easy lookup
        Map<UUID, RRFScore> chunkScores = new HashMap<>();
        
        // Process keyword results (rank 1-based)
        for (int i = 0; i < keywordResults.size(); i++) {
            DocumentChunkRepositoryCustom.ScoredChunk chunk = keywordResults.get(i);
            UUID chunkId = chunk.getChunk().getId();
            int keywordRank = i + 1;
            double keywordScore = 1.0 / (RRF_K + keywordRank);
            
            chunkScores.computeIfAbsent(chunkId, k -> new RRFScore(chunk)).addKeywordScore(keywordScore, keywordRank);
        }
        
        // Process vector results (rank 1-based)
        for (int i = 0; i < vectorResults.size(); i++) {
            DocumentChunkRepositoryCustom.ScoredChunk chunk = vectorResults.get(i);
            UUID chunkId = chunk.getChunk().getId();
            int vectorRank = i + 1;
            double vectorScore = 1.0 / (RRF_K + vectorRank);
            
            chunkScores.computeIfAbsent(chunkId, k -> new RRFScore(chunk)).addVectorScore(vectorScore, vectorRank);
        }
        
        // Sort by combined RRF score
        List<RRFScore> sortedScores = new ArrayList<>(chunkScores.values());
        sortedScores.sort((a, b) -> Double.compare(b.getCombinedScore(), a.getCombinedScore()));
        
        // Convert back to ScoredChunk with combined score
        return sortedScores.stream()
            .limit(limit)
            .map(score -> score.getChunk().withScore(score.getCombinedScore()))
            .collect(Collectors.toList());
    }
    
    /**
     * Helper class to track RRF scores for a chunk.
     */
    private static class RRFScore {
        private final DocumentChunkRepositoryCustom.ScoredChunk chunk;
        private double keywordScore = 0.0;
        private double vectorScore = 0.0;
        private int keywordRank = Integer.MAX_VALUE;
        private int vectorRank = Integer.MAX_VALUE;
        
        public RRFScore(DocumentChunkRepositoryCustom.ScoredChunk chunk) {
            this.chunk = chunk;
        }
        
        public void addKeywordScore(double score, int rank) {
            this.keywordScore += score;
            if (rank < keywordRank) {
                this.keywordRank = rank;
            }
        }
        
        public void addVectorScore(double score, int rank) {
            this.vectorScore += score;
            if (rank < vectorRank) {
                this.vectorRank = rank;
            }
        }
        
        public double getCombinedScore() {
            return keywordScore + vectorScore;
        }
        
        public DocumentChunkRepositoryCustom.ScoredChunk getChunk() {
            return chunk;
        }
    }
}

