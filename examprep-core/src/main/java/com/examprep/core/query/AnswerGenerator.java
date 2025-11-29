package com.examprep.core.query;

import com.examprep.core.query.model.AssembledContext;
import com.examprep.core.query.model.QueryResult;
import com.examprep.llm.client.GeminiClient;
import com.examprep.llm.keymanager.ApiKeyManager;
import com.examprep.llm.prompt.ExamAnswerPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates answers using LLM.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnswerGenerator {
    
    private final GeminiClient geminiClient;
    private final ApiKeyManager apiKeyManager;
    
    /**
     * Generate answer using single LLM call.
     */
    public QueryResult generateSingleCall(
            String question,
            AssembledContext context,
            List<com.examprep.core.query.model.QueryRequest.ChatMessage> chatHistory) {
        
        long startTime = System.currentTimeMillis();
        
        log.info("[ANSWER_GEN] Starting single-call answer generation | questionLength={} | chunks={} | contextTokens={} | chatHistorySize={}", 
            question.length(), context.getChunkCount(), context.getTotalTokens(), 
            chatHistory != null ? chatHistory.size() : 0);
        
        try (var lease = apiKeyManager.acquireKey(30_000)) {
            long keyAcquireTime = System.currentTimeMillis() - startTime;
            log.debug("[ANSWER_GEN] API key acquired | keyId={} | acquireTimeMs={}", 
                lease.getIdentifier(), keyAcquireTime);
            
            // Build prompt with context and conversation history
            long promptStartTime = System.currentTimeMillis();
            String prompt = buildPrompt(question, context, chatHistory);
            long promptBuildTime = System.currentTimeMillis() - promptStartTime;
            
            log.info("[ANSWER_GEN] Prompt built | promptLength={} | buildTimeMs={}", 
                prompt.length(), promptBuildTime);
            
            // Generate answer
            // Only enable Google Search if no context was provided (last resort)
            boolean shouldUseGoogleSearch = context.getChunkCount() == 0 && (chatHistory == null || chatHistory.isEmpty());
            long llmStartTime = System.currentTimeMillis();
            log.info("[ANSWER_GEN] Calling LLM for answer generation | promptLength={} | googleSearchEnabled={} | chunks={}", 
                prompt.length(), shouldUseGoogleSearch, context.getChunkCount());
            
            String answer = geminiClient.generateContent(
                prompt,
                ExamAnswerPrompts.SYSTEM_PROMPT,
                shouldUseGoogleSearch, // Only enable if no documents available
                lease.getApiKey()
            );
            
            long llmDuration = System.currentTimeMillis() - llmStartTime;
            log.info("[ANSWER_GEN] LLM response received | answerLength={} | llmDurationMs={}", 
                answer.length(), llmDuration);
            
            apiKeyManager.reportSuccess(lease.getIdentifier());
            
            // Extract sources
            List<QueryResult.Source> sources = context.getAllChunks().stream()
                .map(chunk -> QueryResult.Source.builder()
                    .documentId(chunk.getDocumentId())
                    .fileName(chunk.getFileName())
                    .pageNumber(chunk.getPageNumber())
                    .slideNumber(chunk.getSlideNumber())
                    .excerpt(truncate(chunk.getContent(), 200))
                    .build())
                .collect(Collectors.toList());
            
            long totalDuration = System.currentTimeMillis() - startTime;
            
            log.info("[ANSWER_GEN] Answer generation completed | totalDurationMs={} | answerLength={} | sourcesCount={} | chunksUsed={}", 
                totalDuration, answer.length(), sources.size(), context.getChunkCount());
            
            return QueryResult.builder()
                .answer(answer)
                .sources(sources)
                .chunksUsed(context.getChunkCount())
                .llmCallsUsed(1)
                .processingMode("single-call")
                .cacheHit(false)
                .build();
            
        } catch (ApiKeyManager.NoAvailableKeyException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[ANSWER_GEN] No API key available | durationMs={}", duration, e);
            throw new RuntimeException("Service temporarily unavailable", e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[ANSWER_GEN] Answer generation failed | durationMs={} | error={}", duration, e.getMessage(), e);
            throw new RuntimeException("Failed to generate answer", e);
        }
    }
    
    private String buildPrompt(
            String question,
            AssembledContext context,
            List<com.examprep.core.query.model.QueryRequest.ChatMessage> chatHistory) {
        
        StringBuilder promptBuilder = new StringBuilder();
        
        // Add conversation history if available
        if (chatHistory != null && !chatHistory.isEmpty()) {
            promptBuilder.append("=== CONVERSATION HISTORY ===\n");
            for (com.examprep.core.query.model.QueryRequest.ChatMessage msg : chatHistory) {
                promptBuilder.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }
            promptBuilder.append("\n");
        }
        
        // Add document context
        if (!context.getContextText().isEmpty()) {
            promptBuilder.append("=== DOCUMENT CONTEXT ===\n");
            promptBuilder.append(context.getContextText());
            promptBuilder.append("\n\n");
        }
        
        // Add question
        promptBuilder.append("=== QUESTION ===\n");
        promptBuilder.append(question);
        promptBuilder.append("\n\n");
        
        promptBuilder.append("Please provide a comprehensive answer using the document context above. ");
        if (context.getChunkCount() == 0) {
            promptBuilder.append("Since no documents are available, you may use Google Search if needed. ");
        } else {
            promptBuilder.append("Answer primarily from the document context provided. ");
        }
        promptBuilder.append("Cite sources using [Source X] format.");
        
        return promptBuilder.toString();
    }
    
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}

