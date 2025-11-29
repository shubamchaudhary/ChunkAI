package com.examprep.core.query;

import com.examprep.core.cache.QueryCacheService;
import com.examprep.core.query.model.*;
import com.examprep.data.entity.QueryHistory;
import com.examprep.data.repository.ChatRepository;
import com.examprep.data.repository.QueryHistoryRepository;
import com.examprep.data.repository.UserRepository;
import com.examprep.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryOrchestrator {
    
    private final QueryAnalyzer queryAnalyzer;
    private final RetrievalEngine retrievalEngine;
    private final ContextAssembler contextAssembler;
    private final AnswerGenerator answerGenerator;
    private final MapReduceOrchestrator mapReduceOrchestrator;
    private final QueryCacheService cacheService;
    private final QueryHistoryRepository historyRepository;
    private final EmbeddingService embeddingService;
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    
    // Configuration
    private static final int MAX_TOKENS_SINGLE_CALL = 100_000;
    private static final int TARGET_CHUNKS = 30;
    private static final int MAX_CHUNKS = 100;
    
    /**
     * Main entry point for processing user queries.
     */
    public QueryResult processQuery(QueryRequest request) {
        String queryId = "query-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        long startTime = System.currentTimeMillis();
        
        log.info("[QUERY_ORCH] Starting query processing | queryId={} | chatId={} | questionLength={} | documentIdsCount={} | useCrossChat={} | chatHistorySize={}", 
            queryId, request.getChatId(), request.getQuestion().length(),
            request.getDocumentIds() != null ? request.getDocumentIds().size() : 0,
            request.isUseCrossChat(), request.getChatHistory() != null ? request.getChatHistory().size() : 0);
        
        try {
            // ============================================
            // STEP 1: Check Cache (FREE - No LLM call)
            // ============================================
            long cacheStartTime = System.currentTimeMillis();
            Optional<QueryResult> cached = cacheService.findCachedAnswer(
                request.getChatId(), 
                request.getQuestion()
            );
            long cacheDuration = System.currentTimeMillis() - cacheStartTime;
            
            if (cached.isPresent()) {
                log.info("[QUERY_ORCH] Cache hit! | queryId={} | cacheCheckDurationMs={}", queryId, cacheDuration);
                return cached.get().withCacheHit(true);
            }
            
            log.debug("[QUERY_ORCH] Cache miss | queryId={} | cacheCheckDurationMs={}", queryId, cacheDuration);
            
            // ============================================
            // STEP 2: Analyze Query (NO LLM - rule-based)
            // ============================================
            long analysisStartTime = System.currentTimeMillis();
            QueryAnalysis analysis = queryAnalyzer.analyze(request.getQuestion());
            long analysisDuration = System.currentTimeMillis() - analysisStartTime;
            
            log.info("[QUERY_ORCH] Query analyzed | queryId={} | queryType={} | keywordsCount={} | entitiesCount={} | complexity={} | analysisDurationMs={}", 
                queryId, analysis.getQueryType(), 
                analysis.getKeywords() != null ? analysis.getKeywords().size() : 0,
                analysis.getEntities() != null ? analysis.getEntities().size() : 0,
                analysis.getComplexity(), analysisDuration);
            
            // ============================================
            // STEP 3: Retrieve Relevant Chunks (NO LLM)
            // ============================================
            long retrievalStartTime = System.currentTimeMillis();
            RetrievalResult retrieval = retrievalEngine.retrieve(
                request.getChatId(),
                request.getDocumentIds(),
                request.getQuestion(),
                analysis,
                MAX_CHUNKS
            );
            long retrievalDuration = System.currentTimeMillis() - retrievalStartTime;
            
            log.info("[QUERY_ORCH] Retrieval completed | queryId={} | chunksRetrieved={} | documentsMatched={} | totalChunksSearched={} | retrievalDurationMs={}", 
                queryId, retrieval.getChunks().size(), retrieval.getDocumentsMatched(), 
                retrieval.getTotalChunksSearched(), retrievalDuration);
            
            // ============================================
            // STEP 4: Assemble Context (NO LLM)
            // ============================================
            long assemblyStartTime = System.currentTimeMillis();
            AssembledContext context = contextAssembler.assemble(
                retrieval.getChunks(),
                analysis,
                TARGET_CHUNKS,
                MAX_TOKENS_SINGLE_CALL
            );
            long assemblyDuration = System.currentTimeMillis() - assemblyStartTime;
            
            log.info("[QUERY_ORCH] Context assembled | queryId={} | chunksUsed={} | totalTokens={} | assemblyDurationMs={}", 
                queryId, context.getChunkCount(), context.getTotalTokens(), assemblyDuration);
            
            // ============================================
            // STEP 5: Generate Answer (LLM calls)
            // ============================================
            long generationStartTime = System.currentTimeMillis();
            QueryResult result;
            
            if (context.getTotalTokens() <= MAX_TOKENS_SINGLE_CALL) {
                log.info("[QUERY_ORCH] Using single-call mode | queryId={} | tokens={} | limit={}", 
                    queryId, context.getTotalTokens(), MAX_TOKENS_SINGLE_CALL);
                result = answerGenerator.generateSingleCall(
                    request.getQuestion(),
                    context,
                    request.getChatHistory()
                );
            } else {
                log.info("[QUERY_ORCH] Using map-reduce mode | queryId={} | tokens={} | limit={} | chunks={}", 
                    queryId, context.getTotalTokens(), MAX_TOKENS_SINGLE_CALL, retrieval.getChunks().size());
                result = mapReduceOrchestrator.process(
                    request.getQuestion(),
                    retrieval.getChunks(),
                    request.getChatHistory()
                );
            }
            long generationDuration = System.currentTimeMillis() - generationStartTime;
            
            log.info("[QUERY_ORCH] Answer generated | queryId={} | processingMode={} | llmCallsUsed={} | answerLength={} | generationDurationMs={}", 
                queryId, result.getProcessingMode(), result.getLlmCallsUsed(), 
                result.getAnswer() != null ? result.getAnswer().length() : 0, generationDuration);
            
            // ============================================
            // STEP 6: Cache Result & Save History
            // ============================================
            long cacheSaveStartTime = System.currentTimeMillis();
            cacheService.cacheResult(
                request.getChatId(), 
                request.getQuestion(), 
                result
            );
            long cacheSaveDuration = System.currentTimeMillis() - cacheSaveStartTime;
            
            log.debug("[QUERY_ORCH] Result cached | queryId={} | cacheSaveDurationMs={}", queryId, cacheSaveDuration);
            
            saveQueryHistory(request, result, startTime);
            
            long totalDuration = System.currentTimeMillis() - startTime;
            
            log.info("[QUERY_ORCH] Query completed successfully | queryId={} | totalDurationMs={} | cacheCheck={} | analysis={} | retrieval={} | assembly={} | generation={} | cacheSave={} | llmCalls={} | sourcesCount={}", 
                queryId, totalDuration, cacheDuration, analysisDuration, retrievalDuration, 
                assemblyDuration, generationDuration, cacheSaveDuration,
                result.getLlmCallsUsed(), result.getSources() != null ? result.getSources().size() : 0);
            
            return result;
            
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            log.error("[QUERY_ORCH] Query processing failed | queryId={} | durationMs={} | error={}", 
                queryId, totalDuration, e.getMessage(), e);
            throw new QueryProcessingException("Failed to process query", e);
        }
    }
    
    private void saveQueryHistory(QueryRequest request, QueryResult result, long startTime) {
        long historyStartTime = System.currentTimeMillis();
        try {
            log.debug("[QUERY_ORCH] Saving query history | chatId={}", request.getChatId());
            
            // Load user and chat entities
            com.examprep.data.entity.User user = userRepository.findById(request.getUserId())
                .orElse(null);
            
            com.examprep.data.entity.Chat chat = chatRepository.findById(request.getChatId())
                .orElse(null);
            
            if (user == null || chat == null) {
                log.warn("[QUERY_ORCH] User or chat not found, skipping history save | userId={} | chatId={}", 
                    request.getUserId(), request.getChatId());
                return;
            }
            
            // Generate query embedding
            long embeddingStartTime = System.currentTimeMillis();
            float[] queryEmbedding = embeddingService.generateEmbedding(request.getQuestion());
            long embeddingDuration = System.currentTimeMillis() - embeddingStartTime;
            
            log.debug("[QUERY_ORCH] Generated query embedding | chatId={} | embeddingDurationMs={}", 
                request.getChatId(), embeddingDuration);
            
            QueryHistory history = QueryHistory.builder()
                .user(user)
                .chat(chat)
                .queryText(request.getQuestion())
                .queryEmbedding(queryEmbedding)
                .marksRequested(request.getMarks())
                .answerText(result.getAnswer())
                .sourcesUsed(convertSourcesToJson(result.getSources()))
                .chunksRetrieved(result.getChunksUsed())
                .llmCallsUsed(result.getLlmCallsUsed())
                .totalTimeMs((int) (System.currentTimeMillis() - startTime))
                .build();
            
            historyRepository.save(history);
            long historyDuration = System.currentTimeMillis() - historyStartTime;
            
            log.debug("[QUERY_ORCH] Query history saved | chatId={} | historySaveDurationMs={}", 
                request.getChatId(), historyDuration);
        } catch (Exception e) {
            long historyDuration = System.currentTimeMillis() - historyStartTime;
            log.warn("[QUERY_ORCH] Failed to save query history | chatId={} | durationMs={} | error={}", 
                request.getChatId(), historyDuration, e.getMessage(), e);
        }
    }
    
    private String truncate(String text, int maxLength) {
        return text.length() > maxLength 
            ? text.substring(0, maxLength) + "..." 
            : text;
    }
    
    private String convertSourcesToJson(List<QueryResult.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sources.size(); i++) {
            QueryResult.Source source = sources.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"documentId\":\"").append(source.getDocumentId()).append("\",");
            json.append("\"fileName\":\"").append(source.getFileName()).append("\"");
            if (source.getPageNumber() != null) {
                json.append(",\"pageNumber\":").append(source.getPageNumber());
            }
            if (source.getSlideNumber() != null) {
                json.append(",\"slideNumber\":").append(source.getSlideNumber());
            }
            json.append("}");
        }
        json.append("]");
        return json.toString();
    }
    
    public static class QueryProcessingException extends RuntimeException {
        public QueryProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

