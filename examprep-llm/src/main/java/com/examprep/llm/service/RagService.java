package com.examprep.llm.service;

import com.examprep.data.entity.DocumentChunk;
import com.examprep.data.repository.DocumentChunkRepository;
import com.examprep.llm.client.GeminiClient;
import com.examprep.llm.keymanager.ApiKeyManager;
import com.examprep.llm.prompt.ExamAnswerPrompts;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final EmbeddingService embeddingService;
    private final GeminiClient geminiClient;
    private final DocumentChunkRepository chunkRepository;
    private final com.examprep.llm.client.GeminiConfig config;
    private final ApiKeyManager apiKeyManager;
    
    // Thread pool for parallel API calls
    private final ExecutorService executorService = Executors.newFixedThreadPool(3);
    
    private static final int TOP_CHUNKS = 100; // Reduced from 150 to speed up processing
    
    /**
     * Enhanced RAG pipeline with parallel prompt generation:
     * Stage 1: Retrieve top 150 similar chunks from vector DB
     * Stage 2: Divide chunks into 3 parts (50 each), call 3 APIs in parallel to generate prompts
     * Stage 3: Combine the 3 prompt results
     * Stage 4: Final LLM call with combined prompt, chat history, and user question
     */
    public RagResult query(
            UUID userId,
            String question,
            Integer marks,
            List<UUID> documentIds,
            String customFormat,
            UUID chatId,
            boolean useCrossChat,
            List<String> conversationHistory
    ) {
        long startTime = System.currentTimeMillis();
        
        // ============================================
        // STAGE 1: Vector DB Retrieval - Get Top 150 Chunks
        // ============================================
        log.info("=== STAGE 1: Vector DB Retrieval (Top {} chunks) ===", TOP_CHUNKS);
        
        // Step 1.1: Generate embedding for the question
        log.info("Generating embedding for query: {}", question.substring(0, Math.min(50, question.length())));
        float[] queryEmbedding = embeddingService.generateEmbedding(question);
        long embeddingTime = System.currentTimeMillis() - startTime;
        
        // Step 1.2: Retrieve top 150 similar chunks from vector DB
        log.info("Searching for top {} similar chunks for user: {}, chat: {}, crossChat: {}", 
            TOP_CHUNKS, userId, chatId, useCrossChat);
        String vectorString = embeddingService.toVectorString(queryEmbedding);
        
        UUID[] docIdArray = (documentIds != null && !documentIds.isEmpty()) 
            ? documentIds.toArray(new UUID[0]) 
            : null;
        
        // Always retrieve top 150 chunks (or whatever is available)
        List<DocumentChunk> relevantChunks = chunkRepository.findSimilarChunksCustom(
            userId,
            vectorString,
            docIdArray,
            chatId,
            useCrossChat,
            TOP_CHUNKS
        );
        long retrievalTime = System.currentTimeMillis() - startTime - embeddingTime;
        
        log.info("Found {} relevant chunks (requested: {})", relevantChunks.size(), TOP_CHUNKS);
        
        // Filter chunks based on conversation history if needed (for follow-up questions)
        List<DocumentChunk> filteredChunks = filterChunksByHistory(relevantChunks, conversationHistory, question);
        
        // Only use internet if we have NO chunks and NO conversation history
        boolean shouldUseInternet = filteredChunks.isEmpty() && 
            (conversationHistory == null || conversationHistory.isEmpty());
        
        if (shouldUseInternet) {
            log.info("No chunks or conversation history found. Will use internet search.");
        }
        
        // ============================================
        // STAGE 2: Parallel Prompt Generation (3 APIs)
        // ============================================
        log.info("=== STAGE 2: Parallel Prompt Generation (3 APIs) ===");
        
        // Build conversation history context
        String conversationContext = buildConversationContext(conversationHistory);
        
        // Divide chunks into 3 parts
        List<List<DocumentChunk>> chunkParts = divideChunks(filteredChunks, 3);
        log.info("Divided {} chunks into {} parts: {}", 
            filteredChunks.size(), chunkParts.size(),
            chunkParts.stream().map(List::size).collect(Collectors.toList()));
        
        // Get API keys (at least 3, or use available keys)
        List<String> apiKeys = apiKeyManager.getAllApiKeys();
        int numApis = Math.min(3, apiKeys.size());
        int numParts = chunkParts.size();
        int partsToProcess = Math.min(numApis, numParts);
        
        log.info("API keys available: {}, Parts to process: {}, Chunk parts: {}", 
            apiKeys.size(), partsToProcess, numParts);
        
        // Create prompts for each part - always use all available parts up to 3
        List<CompletableFuture<String>> promptFutures = new ArrayList<>();
        for (int i = 0; i < partsToProcess; i++) {
            final int apiIndex = i;
            final List<DocumentChunk> chunkPart = chunkParts.get(i);
            final String apiKey = apiKeys.get(apiIndex);
            
            log.info("Creating future for API {} with {} chunks", apiIndex + 1, chunkPart.size());
            
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    log.info("Calling API {} with {} chunks", apiIndex + 1, chunkPart.size());
                    String result = generatePromptForChunks(
                        chunkPart, 
                        question, 
                        conversationContext, 
                        marks, 
                        customFormat,
                        apiKey
                    );
                    log.info("API {} completed with result length: {}", apiIndex + 1, result.length());
                    return result;
                } catch (Exception e) {
                    log.error("Error generating prompt with API {}: {}", apiIndex + 1, e.getMessage(), e);
                    return ""; // Return empty string on error
                }
            }, executorService);
            
            promptFutures.add(future);
        }
        
        log.info("Created {} futures for parallel processing", promptFutures.size());
        
        // Wait for all prompts to complete with timeout (10 seconds per API call)
        log.info("Waiting for {} futures to complete (timeout: 30s total)...", promptFutures.size());
        List<String> generatedPrompts = new ArrayList<>();
        long promptStartTime = System.currentTimeMillis();
        long maxWaitTime = 30000; // 30 seconds max for all prompt generation
        
        for (int i = 0; i < promptFutures.size(); i++) {
            try {
                long remainingTime = maxWaitTime - (System.currentTimeMillis() - promptStartTime);
                if (remainingTime <= 0) {
                    log.warn("Timeout reached, skipping remaining prompt generation");
                    break;
                }
                
                String prompt = promptFutures.get(i).get(Math.min(remainingTime, 15000), TimeUnit.MILLISECONDS);
                if (!prompt.isEmpty()) {
                    generatedPrompts.add(prompt);
                    log.info("Future {} completed successfully, prompt length: {}", i + 1, prompt.length());
                } else {
                    log.warn("Future {} returned empty prompt", i + 1);
                }
            } catch (TimeoutException e) {
                log.warn("Future {} timed out, skipping", i + 1);
            } catch (Exception e) {
                log.error("Future {} failed: {}", i + 1, e.getMessage(), e);
            }
        }
        
        long promptGenerationTime = System.currentTimeMillis() - startTime - embeddingTime - retrievalTime;
        log.info("=== STAGE 2 COMPLETE: Generated {} prompts in parallel ({}ms) ===", 
            generatedPrompts.size(), promptGenerationTime);
        
        // ============================================
        // STAGE 3: Combine Prompts
        // ============================================
        log.info("=== STAGE 3: Combining Prompts ===");
        String combinedPrompt = combinePrompts(generatedPrompts, question, conversationContext, marks, customFormat);
        log.info("=== STAGE 3 COMPLETE: Combined prompt length: {} ===", combinedPrompt.length());
        
        // ============================================
        // STAGE 4: Final LLM Call
        // ============================================
        log.info("=== STAGE 4: Final LLM Call ===");
        
        String answerSystemPrompt = ExamAnswerPrompts.SYSTEM_PROMPT;
        String apiKey = apiKeyManager.getNextApiKey(); // Round-robin for final call
        log.info("Generating final answer using combined prompt (internet: {})", shouldUseInternet);
        
        String answer = geminiClient.generateContent(
            combinedPrompt, 
            answerSystemPrompt, 
            shouldUseInternet,
            apiKey
        );
        
        long answerGenerationTime = System.currentTimeMillis() - startTime - embeddingTime - retrievalTime - promptGenerationTime;
        log.info("=== STAGE 4 COMPLETE: Final answer generated ({}ms) ===", answerGenerationTime);
        
        // Build response
        List<SourceInfo> sources = filteredChunks.stream()
            .map(chunk -> SourceInfo.builder()
                .documentId(chunk.getDocument().getId())
                .fileName(chunk.getDocument().getFileName())
                .pageNumber(chunk.getPageNumber())
                .slideNumber(chunk.getSlideNumber())
                .excerpt(truncate(chunk.getContent(), 200))
                .build())
            .collect(Collectors.toList());
        
        return RagResult.builder()
            .answer(answer)
            .sources(sources)
            .retrievalTimeMs(retrievalTime)
            .generationTimeMs(promptGenerationTime + answerGenerationTime)
            .chunksUsed(filteredChunks.size())
            .build();
    }
    
    /**
     * Filter chunks based on conversation history for follow-up questions.
     */
    private List<DocumentChunk> filterChunksByHistory(
            List<DocumentChunk> chunks,
            List<String> conversationHistory,
            String question
    ) {
        // Check if this is a follow-up question
        boolean isFollowUpWithReferences = conversationHistory != null && !conversationHistory.isEmpty() &&
            (question.toLowerCase().contains(" it") || question.toLowerCase().contains("this") ||
             question.toLowerCase().contains("that") || question.toLowerCase().contains("who wrote") ||
             question.toLowerCase().contains("the book") || question.toLowerCase().contains("the author") ||
             question.toLowerCase().contains("what is it") || question.toLowerCase().contains("tell me more"));
        
        if (!isFollowUpWithReferences || conversationHistory.isEmpty()) {
            return chunks; // No filtering needed
        }
        
        // Extract document names from conversation history
        Set<String> historyDocumentNames = new HashSet<>();
        Set<String> historyDocumentBaseNames = new HashSet<>();
        
        for (int i = 0; i < conversationHistory.size(); i += 2) {
            if (i + 1 < conversationHistory.size()) {
                String answer = conversationHistory.get(i + 1);
                if (answer != null) {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                        "([A-Za-z0-9_\\-\\.]+\\.pdf)", java.util.regex.Pattern.CASE_INSENSITIVE);
                    java.util.regex.Matcher matcher = pattern.matcher(answer);
                    while (matcher.find()) {
                        String docName = matcher.group(1).toLowerCase();
                        historyDocumentNames.add(docName);
                        String baseName = docName.replace(".pdf", "").replace("_", "").replace("-", "").replace(".", "");
                        historyDocumentBaseNames.add(baseName);
                    }
                }
            }
        }
        
        if (historyDocumentNames.isEmpty() && historyDocumentBaseNames.isEmpty()) {
            return chunks; // No documents found in history, return all chunks
        }
        
        // Filter chunks
        return chunks.stream()
            .filter(chunk -> {
                String fileName = chunk.getDocument().getFileName().toLowerCase();
                String originalFileName = chunk.getDocument().getOriginalFileName() != null 
                    ? chunk.getDocument().getOriginalFileName().toLowerCase() 
                    : fileName;
                
                String fileNameBase = fileName.replace(".pdf", "").replace("_", "").replace("-", "").replace(".", "").replace(" ", "");
                String originalFileNameBase = originalFileName.replace(".pdf", "").replace("_", "").replace("-", "").replace(".", "").replace(" ", "");
                
                boolean exactMatch = historyDocumentNames.contains(fileName) || historyDocumentNames.contains(originalFileName);
                boolean baseMatch = historyDocumentBaseNames.stream().anyMatch(historyBase -> 
                    fileNameBase.contains(historyBase) || 
                    historyBase.contains(fileNameBase) ||
                    originalFileNameBase.contains(historyBase) ||
                    historyBase.contains(originalFileNameBase)
                );
                
                return exactMatch || baseMatch;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Build conversation history context.
     */
    private String buildConversationContext(List<String> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return "";
        }
        
        StringBuilder historyBuilder = new StringBuilder();
        int maxHistoryItems = config.getMaxConversationHistory() * 2;
        int startIndex = Math.max(0, conversationHistory.size() - maxHistoryItems);
        int messageCount = 0;
        
        for (int i = startIndex; i < conversationHistory.size() && messageCount < maxHistoryItems; i += 2) {
            if (i + 1 < conversationHistory.size()) {
                String prevQuestion = conversationHistory.get(i);
                String prevAnswer = conversationHistory.get(i + 1);
                
                historyBuilder.append("Message ").append((messageCount / 2) + 1).append(":\n");
                historyBuilder.append("  User: ").append(prevQuestion).append("\n");
                historyBuilder.append("  Assistant: ").append(prevAnswer).append("\n\n");
                messageCount += 2;
            }
        }
        
        return historyBuilder.toString();
    }
    
    /**
     * Divide chunks into N parts (for parallel processing).
     */
    private List<List<DocumentChunk>> divideChunks(List<DocumentChunk> chunks, int numParts) {
        List<List<DocumentChunk>> parts = new ArrayList<>();
        int chunkSize = chunks.size();
        int partSize = Math.max(1, chunkSize / numParts);
        
        for (int i = 0; i < numParts; i++) {
            int start = i * partSize;
            int end = (i == numParts - 1) ? chunkSize : Math.min(start + partSize, chunkSize);
            
            if (start < chunkSize) {
                parts.add(new ArrayList<>(chunks.subList(start, end)));
            } else {
                parts.add(new ArrayList<>());
            }
        }
        
        return parts;
    }
    
    /**
     * Generate prompt for a specific chunk part.
     */
    private String generatePromptForChunks(
            List<DocumentChunk> chunks,
            String question,
            String conversationContext,
            Integer marks,
            String customFormat,
            String apiKey
    ) {
        String promptCreationSystemPrompt = """
            You are an expert prompt engineer specializing in creating optimized prompts for AI assistants.
            
            Your task is to analyze the provided document chunks and conversation history, then create a comprehensive,
            well-organized prompt section that synthesizes the information from these chunks.
            
            CRITICAL REQUIREMENTS:
            1. Synthesize information from the provided chunks into coherent, unified context
            2. Eliminate redundancy while preserving all critical information
            3. Organize information logically (chronologically, by topic, or by importance)
            4. Highlight key concepts, definitions, examples, and relationships
            5. Do NOT include the question or conversation history in your output - only the synthesized context
            6. Focus on extracting and organizing the most relevant information from the chunks
            7. Ensure completeness - don't lose important details
            
            OUTPUT FORMAT:
            Create a well-structured context section that can be combined with other similar sections.
            The output should be ready to be merged with other context sections.
            """;
        
        StringBuilder userPrompt = new StringBuilder();
        
        if (!conversationContext.isEmpty()) {
            userPrompt.append("=== CONVERSATION HISTORY ===\n");
            userPrompt.append(conversationContext);
            userPrompt.append("\n=== END OF CONVERSATION HISTORY ===\n\n");
        }
        
        if (!chunks.isEmpty()) {
            userPrompt.append("=== DOCUMENT CHUNKS TO SYNTHESIZE ===\n");
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                userPrompt.append(String.format(
                    "[Chunk %d: %s, Page/Slide %d]\n%s\n\n",
                    i + 1,
                    chunk.getDocument().getFileName(),
                    chunk.getSlideNumber() != null ? chunk.getSlideNumber() : chunk.getPageNumber(),
                    chunk.getContent()
                ));
            }
            userPrompt.append("=== END OF DOCUMENT CHUNKS ===\n\n");
        }
        
        userPrompt.append("=== QUESTION (for context only) ===\n");
        userPrompt.append(question).append("\n\n");
        
        if (marks != null) {
            userPrompt.append("=== MARKS ALLOCATION ===\n");
            userPrompt.append("Marks: ").append(marks).append("\n\n");
        }
        
        userPrompt.append("=== YOUR TASK ===\n");
        userPrompt.append("Synthesize the document chunks above into a comprehensive, well-organized context section.\n");
        userPrompt.append("Focus on extracting key information, eliminating redundancy, and organizing logically.\n");
        userPrompt.append("Do NOT include the question or conversation history in your output - only the synthesized context from chunks.");
        
        // Use maxOutputTokens for prompt generation to avoid truncation
        // Gemini 2.5 Flash supports up to 8192 tokens
        return geminiClient.generateContent(
            userPrompt.toString(),
            promptCreationSystemPrompt,
            false, // No internet search for prompt generation
            apiKey,
            8192 // Max limit for Gemini 2.5 Flash to avoid truncation
        );
    }
    
    /**
     * Combine multiple prompts into a single comprehensive prompt.
     */
    private String combinePrompts(
            List<String> prompts,
            String question,
            String conversationContext,
            Integer marks,
            String customFormat
    ) {
        StringBuilder combined = new StringBuilder();
        
        // Add conversation history if available
        if (!conversationContext.isEmpty()) {
            combined.append("=== CONVERSATION HISTORY ===\n");
            combined.append(conversationContext);
            combined.append("\n=== END OF CONVERSATION HISTORY ===\n\n");
        }
        
        // Combine all generated prompts
        combined.append("=== SYNTHESIZED DOCUMENT CONTEXT ===\n");
        combined.append("The following context was synthesized from multiple document chunks:\n\n");
        
        for (int i = 0; i < prompts.size(); i++) {
            combined.append("--- Context Section ").append(i + 1).append(" ---\n");
            combined.append(prompts.get(i));
            combined.append("\n\n");
        }
        
        combined.append("=== END OF DOCUMENT CONTEXT ===\n\n");
        
        // Add question
        combined.append("=== QUESTION ===\n");
        combined.append(question).append("\n\n");
        
        // Add marks if specified
        if (marks != null) {
            combined.append("=== MARKS ALLOCATION ===\n");
            combined.append("Marks: ").append(marks).append("\n");
            combined.append("Structure your answer accordingly.\n\n");
        }
        
        // Add custom format if specified
        if (customFormat != null && !customFormat.isBlank()) {
            combined.append("=== ADDITIONAL FORMAT REQUIREMENTS ===\n");
            combined.append(customFormat).append("\n\n");
        }
        
        // Add instructions
        combined.append("=== INSTRUCTIONS ===\n");
        combined.append("Provide a comprehensive answer using the document context above.\n");
        combined.append("If information is missing or the question cannot be fully answered from the context, ");
        combined.append("use Google Search to find the missing information.\n");
        combined.append("Cite sources using [Source X] format for documents, and explicitly mention when using internet search.\n");
        combined.append("Ensure your answer is complete, well-structured, and addresses all aspects of the question.");
        
        return combined.toString();
    }
    
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
    
    @Data
    @Builder
    public static class RagResult {
        private String answer;
        private List<SourceInfo> sources;
        private Long retrievalTimeMs;
        private Long generationTimeMs;
        private Integer chunksUsed;
    }
    
    @Data
    @Builder
    public static class SourceInfo {
        private UUID documentId;
        private String fileName;
        private Integer pageNumber;
        private Integer slideNumber;
        private String excerpt;
    }
}
