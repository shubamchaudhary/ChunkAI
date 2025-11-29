package com.examprep.core.query;

import com.examprep.core.query.model.QueryAnalysis;
import com.examprep.core.query.model.RetrievalResult;
import com.examprep.data.repository.DocumentChunkRepositoryCustom;
import com.examprep.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retrieval engine using hybrid search (keyword + vector).
 * No longer uses document summaries - all search is at chunk level.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalEngine {
    
    private final EmbeddingService embeddingService;
    private final HybridSearchService hybridSearchService;
    
    /**
     * Hybrid retrieval strategy:
     * - Uses HybridSearchService to combine keyword and vector search
     * - Applies diversity filter to ensure good coverage
     */
    public RetrievalResult retrieve(
            UUID chatId,
            List<UUID> documentIds,
            String question,
            QueryAnalysis analysis,
            int maxChunks) {
        
        long retrievalStartTime = System.currentTimeMillis();
        
        log.info("[RETRIEVAL] Starting hybrid retrieval | chatId={} | questionLength={} | documentIdsCount={} | maxChunks={}", 
            chatId, question.length(), documentIds != null ? documentIds.size() : 0, maxChunks);
        
        // Build search query from question and keywords
        String searchQuery = buildSearchQuery(question, analysis);
        
        // Generate question embedding for vector search (if embeddings are available)
        float[] questionEmbedding = null;
        String queryEmbeddingString = null;
        
        try {
            long embeddingStartTime = System.currentTimeMillis();
            questionEmbedding = embeddingService.generateEmbedding(question);
            queryEmbeddingString = embeddingService.toVectorString(questionEmbedding);
            long embeddingDuration = System.currentTimeMillis() - embeddingStartTime;
            
            log.info("[RETRIEVAL] Question embedding generated | chatId={} | embeddingDurationMs={}", 
                chatId, embeddingDuration);
        } catch (Exception e) {
            log.warn("[RETRIEVAL] Failed to generate query embedding, will use keyword-only search | chatId={} | error={}", 
                chatId, e.getMessage());
        }
        
        // Perform hybrid search
        long hybridSearchStartTime = System.currentTimeMillis();
        List<DocumentChunkRepositoryCustom.ScoredChunk> hybridResults = hybridSearchService.hybridSearch(
            chatId,
            documentIds,
            searchQuery,
            queryEmbeddingString,  // May be null - hybrid search will degrade to keyword-only
            maxChunks * 2  // Get extra for diversity filtering
        );
        long hybridSearchDuration = System.currentTimeMillis() - hybridSearchStartTime;
        
        log.info("[RETRIEVAL] Hybrid search completed | chatId={} | chunksFound={} | durationMs={}", 
            chatId, hybridResults.size(), hybridSearchDuration);
        
        if (hybridResults.isEmpty()) {
            log.warn("[RETRIEVAL] No relevant chunks found | chatId={}", chatId);
            return RetrievalResult.empty();
        }
        
        // Apply diversity filter
        long diversityStartTime = System.currentTimeMillis();
        List<DocumentChunkRepositoryCustom.ScoredChunk> diverseChunks = applyDiversityFilter(
            hybridResults, 
            maxChunks
        );
        long diversityDuration = System.currentTimeMillis() - diversityStartTime;
        long totalRetrievalDuration = System.currentTimeMillis() - retrievalStartTime;
        
        log.info("[RETRIEVAL] Diversity filter completed | chatId={} | finalChunks={} | durationMs={}", 
            chatId, diverseChunks.size(), diversityDuration);
        
        // Count unique documents
        Set<UUID> uniqueDocIds = diverseChunks.stream()
            .map(chunk -> chunk.getDocumentId())
            .collect(Collectors.toSet());
        
        log.info("[RETRIEVAL] Hybrid retrieval completed | chatId={} | totalDurationMs={} | documentsMatched={} | chunksSearched={} | finalChunks={}", 
            chatId, totalRetrievalDuration, uniqueDocIds.size(), 
            hybridResults.size(), diverseChunks.size());
        
        return RetrievalResult.builder()
            .chunks(diverseChunks)
            .documentsMatched(uniqueDocIds.size())
            .totalChunksSearched(hybridResults.size())
            .build();
    }
    
    /**
     * Build search query from question and extracted keywords.
     */
    private String buildSearchQuery(String question, QueryAnalysis analysis) {
        // Combine question with keywords for better full-text search
        StringBuilder queryBuilder = new StringBuilder(question);
        
        if (analysis.getKeywords() != null && !analysis.getKeywords().isEmpty()) {
            for (String keyword : analysis.getKeywords()) {
                if (keyword.length() > 2) {  // Only add meaningful keywords
                    queryBuilder.append(" ").append(keyword);
                }
            }
        }
        
        return queryBuilder.toString();
    }
    
    /**
     * Ensure diversity in results.
     */
    private List<DocumentChunkRepositoryCustom.ScoredChunk> applyDiversityFilter(
            List<DocumentChunkRepositoryCustom.ScoredChunk> chunks, 
            int maxChunks) {
        
        List<DocumentChunkRepositoryCustom.ScoredChunk> selected = new ArrayList<>();
        Map<UUID, Integer> chunksPerDocument = new HashMap<>();
        Map<String, Integer> chunksPerSection = new HashMap<>();
        Set<String> contentHashes = new HashSet<>();
        
        int maxChunksPerDocument = Math.max(5, maxChunks / 4);
        int maxChunksPerSection = 3;
        
        for (DocumentChunkRepositoryCustom.ScoredChunk chunk : chunks) {
            if (selected.size() >= maxChunks) break;
            
            // Skip duplicates
            String contentHash = hashContent(chunk.getContent());
            if (contentHashes.contains(contentHash)) continue;
            
            // Limit per document
            UUID docId = chunk.getDocumentId();
            int docCount = chunksPerDocument.getOrDefault(docId, 0);
            if (docCount >= maxChunksPerDocument) continue;
            
            // Limit per section
            String sectionKey = docId + ":" + (chunk.getSectionTitle() != null ? chunk.getSectionTitle() : "none");
            int sectionCount = chunksPerSection.getOrDefault(sectionKey, 0);
            if (sectionCount >= maxChunksPerSection) continue;
            
            // Skip very low relevance
            if (chunk.getScore() < 0.1) continue;
            
            // Add chunk
            selected.add(chunk);
            contentHashes.add(contentHash);
            chunksPerDocument.put(docId, docCount + 1);
            chunksPerSection.put(sectionKey, sectionCount + 1);
        }
        
        return selected;
    }
    
    private String hashContent(String content) {
        return Integer.toHexString(content.hashCode());
    }
}
