package com.examprep.llm.service;

import com.examprep.data.entity.DocumentChunk;
import com.examprep.data.repository.DocumentChunkRepository;
import com.examprep.llm.client.GeminiClient;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final EmbeddingService embeddingService;
    private final GeminiClient geminiClient;
    private final DocumentChunkRepository chunkRepository;
    private final com.examprep.llm.client.GeminiConfig config;
    
    /**
     * Main RAG pipeline with two-stage LLM prompt generation:
     * Stage 1: Retrieve relevant chunks from vector DB
     * Stage 2: First LLM call - Create optimized prompt from chunks + conversation history
     * Stage 3: Second LLM call - Generate final answer using the optimized prompt
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
        // STAGE 1: Vector DB Retrieval
        // ============================================
        log.info("=== STAGE 1: Vector DB Retrieval ===");
        
        // Step 1.1: Generate embedding for the question
        log.info("Generating embedding for query: {}", question.substring(0, Math.min(50, question.length())));
        float[] queryEmbedding = embeddingService.generateEmbedding(question);
        long embeddingTime = System.currentTimeMillis() - startTime;
        
        // Step 1.2: Retrieve similar chunks from vector DB
        log.info("Searching for similar chunks for user: {}, chat: {}, crossChat: {}", 
            userId, chatId, useCrossChat);
        String vectorString = embeddingService.toVectorString(queryEmbedding);
        
        UUID[] docIdArray = (documentIds != null && !documentIds.isEmpty()) 
            ? documentIds.toArray(new UUID[0]) 
            : null;
        
        log.debug("Vector string length: {}, Document IDs: {}, Chat scope: {}", 
            vectorString.length(), 
            docIdArray != null ? docIdArray.length : "all",
            useCrossChat ? "cross-chat" : "chat-scoped");
        
        // Check if this is a follow-up question that might be answerable from conversation history
        boolean isFollowUpWithReferences = conversationHistory != null && !conversationHistory.isEmpty() &&
            (question.toLowerCase().contains(" it") || question.toLowerCase().contains("this") ||
             question.toLowerCase().contains("that") || question.toLowerCase().contains("who wrote") ||
             question.toLowerCase().contains("the book") || question.toLowerCase().contains("the author") ||
             question.toLowerCase().contains("what is it") || question.toLowerCase().contains("tell me more"));
        
        // Use configurable max chunks for larger context window
        int maxChunks = isFollowUpWithReferences ? 
            Math.min(20, config.getMaxContextChunks() / 5) : // Fewer chunks for follow-up questions
            config.getMaxContextChunks(); // Full context for new questions
        
        List<DocumentChunk> relevantChunks = chunkRepository.findSimilarChunksCustom(
            userId,
            vectorString,
            docIdArray,
            chatId,
            useCrossChat,
            maxChunks
        );
        long retrievalTime = System.currentTimeMillis() - startTime - embeddingTime;
        
        log.info("Found {} relevant chunks for query. Is follow-up: {}", relevantChunks.size(), isFollowUpWithReferences);
        
        // For follow-up questions, filter chunks to only include those from documents mentioned in conversation history
        List<DocumentChunk> filteredChunks = relevantChunks;
        if (isFollowUpWithReferences && !conversationHistory.isEmpty()) {
            // Extract document names from conversation history
            Set<String> historyDocumentNames = new HashSet<>();
            Set<String> historyDocumentBaseNames = new HashSet<>(); // For fuzzy matching
            
            for (int i = 0; i < conversationHistory.size(); i += 2) {
                if (i + 1 < conversationHistory.size()) {
                    String answer = conversationHistory.get(i + 1);
                    // Look for document names in the answer (e.g., "Mindset_by_Carol_S._Dweck.pdf")
                    if (answer != null) {
                        // Extract document names from sources mentioned in conversation history
                        // Look for patterns like "Source X: filename.pdf" or "filename.pdf (Page Y)" or just "filename.pdf"
                        // Also look for patterns like "Mindset_by_Carol_S._Dweck.pdf" in sources section
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([A-Za-z0-9_\\-\\.]+\\.pdf)", java.util.regex.Pattern.CASE_INSENSITIVE);
                        java.util.regex.Matcher matcher = pattern.matcher(answer);
                        while (matcher.find()) {
                            String docName = matcher.group(1).toLowerCase();
                            historyDocumentNames.add(docName);
                            // Also add base name for fuzzy matching (remove underscores, hyphens, .pdf)
                            String baseName = docName.replace(".pdf", "").replace("_", "").replace("-", "").replace(".", "");
                            historyDocumentBaseNames.add(baseName);
                            log.info("Found document in conversation history: {} (base: {})", docName, baseName);
                        }
                        
                        // Also check for document names in "Sources:" section format
                        // Pattern: "• filename.pdf" or "filename.pdf (Page X)"
                        if (answer.contains("Sources:") || answer.contains("•")) {
                            java.util.regex.Pattern sourcePattern = java.util.regex.Pattern.compile("•\\s*([A-Za-z0-9_\\-\\.]+\\.pdf)", java.util.regex.Pattern.CASE_INSENSITIVE);
                            java.util.regex.Matcher sourceMatcher = sourcePattern.matcher(answer);
                            while (sourceMatcher.find()) {
                                String docName = sourceMatcher.group(1).toLowerCase();
                                historyDocumentNames.add(docName);
                                String baseName = docName.replace(".pdf", "").replace("_", "").replace("-", "").replace(".", "");
                                historyDocumentBaseNames.add(baseName);
                                log.info("Found document in sources section: {} (base: {})", docName, baseName);
                            }
                        }
                    }
                }
            }
            
            log.info("Documents extracted from conversation history: {} (base names: {})", 
                historyDocumentNames, historyDocumentBaseNames);
            
            // Filter chunks to only include those from documents mentioned in history
            if (!historyDocumentNames.isEmpty() || !historyDocumentBaseNames.isEmpty()) {
                log.info("Filtering chunks. History docs: {}, Base names: {}", historyDocumentNames, historyDocumentBaseNames);
                
                filteredChunks = relevantChunks.stream()
                    .filter(chunk -> {
                        String fileName = chunk.getDocument().getFileName().toLowerCase();
                        String originalFileName = chunk.getDocument().getOriginalFileName() != null 
                            ? chunk.getDocument().getOriginalFileName().toLowerCase() 
                            : fileName;
                        
                        // Normalize file names for comparison
                        String fileNameBase = fileName.replace(".pdf", "").replace("_", "").replace("-", "").replace(".", "").replace(" ", "");
                        String originalFileNameBase = originalFileName.replace(".pdf", "").replace("_", "").replace("-", "").replace(".", "").replace(" ", "");
                        
                        // Check exact match first
                        boolean exactMatch = historyDocumentNames.contains(fileName) || historyDocumentNames.contains(originalFileName);
                        
                        // Check base name match (fuzzy matching)
                        boolean baseMatch = historyDocumentBaseNames.stream().anyMatch(historyBase -> 
                            fileNameBase.contains(historyBase) || 
                            historyBase.contains(fileNameBase) ||
                            originalFileNameBase.contains(historyBase) ||
                            historyBase.contains(originalFileNameBase)
                        );
                        
                        boolean matches = exactMatch || baseMatch;
                        
                        if (!matches) {
                            log.info("FILTERED OUT chunk from document: {} (original: {}) - not in conversation history. History docs: {}", 
                                fileName, originalFileName, historyDocumentNames);
                        } else {
                            log.info("KEPT chunk from document: {} (original: {}) - matches conversation history", 
                                fileName, originalFileName);
                        }
                        return matches;
                    })
                    .collect(Collectors.toList());
                
                log.info("Filtered chunks from {} to {} based on conversation history documents", 
                    relevantChunks.size(), filteredChunks.size());
                
                // If all chunks were filtered out, return empty list (answer will come from conversation history only)
                if (filteredChunks.isEmpty()) {
                    log.info("All chunks filtered out. Answer will come from conversation history only.");
                }
            }
        }
        
        // Don't return early - allow LLM to use internet search even when no chunks are found
        // This enables answering general knowledge questions without uploaded documents
        if (filteredChunks.isEmpty() && (conversationHistory == null || conversationHistory.isEmpty())) {
            log.info("No chunks found for user {} with query: {}. Proceeding with internet search.", userId, question);
        }
        
        // Step 1.3: Build context from filtered chunks
        String retrievedContext = filteredChunks.isEmpty() ? "" : buildContext(filteredChunks);
        
        log.info("=== STAGE 1 COMPLETE: Retrieved {} chunks from vector DB ===", relevantChunks.size());
        
        // ============================================
        // STAGE 2: First LLM Call - Create Optimized Prompt
        // ============================================
        log.info("=== STAGE 2: First LLM Call - Creating Optimized Prompt ===");
        
        // Step 2.1: Build conversation history context (last 10 messages from current chat)
        // Check if current question is a follow-up question with references
        boolean isFollowUpQuestion = conversationHistory != null && !conversationHistory.isEmpty() &&
            (question.toLowerCase().contains(" it") || question.toLowerCase().contains("this") ||
             question.toLowerCase().contains("that") || question.toLowerCase().contains("who wrote") ||
             question.toLowerCase().contains("the book") || question.toLowerCase().contains("the author") ||
             question.toLowerCase().contains("what is it") || question.toLowerCase().contains("tell me more"));
        
        String conversationContext = "";
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            StringBuilder historyBuilder = new StringBuilder();
            
            // Take last N messages (configurable max Q&A pairs)
            int maxHistoryItems = config.getMaxConversationHistory() * 2; // Each Q&A pair = 2 items
            int startIndex = Math.max(0, conversationHistory.size() - maxHistoryItems);
            int messageCount = 0;
            
            for (int i = startIndex; i < conversationHistory.size() && messageCount < maxHistoryItems; i += 2) {
                if (i + 1 < conversationHistory.size()) {
                    String prevQuestion = conversationHistory.get(i);
                    String prevAnswer = conversationHistory.get(i + 1);
                    
                    historyBuilder.append("Message ").append((messageCount / 2) + 1).append(":\n");
                    historyBuilder.append("  User: ").append(prevQuestion).append("\n");
                    // Don't truncate answers - preserve full context including author names and book titles
                    historyBuilder.append("  Assistant: ").append(prevAnswer).append("\n\n");
                    messageCount += 2;
                }
            }
            
            if (historyBuilder.length() > 0) {
                conversationContext = historyBuilder.toString();
                log.info("Added conversation history: {} messages ({} Q&A pairs). Is follow-up question: {}", 
                    messageCount, messageCount / 2, isFollowUpQuestion);
            } else {
                log.info("No valid conversation history available");
            }
        } else {
            log.info("No conversation history available for this chat");
        }
        
        // Step 2.2: Create prompt for first LLM call (prompt creation)
        String promptCreationSystemPrompt = """
            You are an expert prompt engineer specializing in creating optimized prompts for AI assistants that help users
            answer questions using their uploaded documents and internet search when needed.
            
            Your task is to analyze retrieved document chunks and conversation history, then create a highly optimized,
            comprehensive prompt that will enable another LLM to generate accurate, well-structured answers as a general AI assistant.
            
            CRITICAL REQUIREMENTS FOR PROMPT CREATION:
            
            1. CONVERSATION CONTEXT INTEGRATION (HIGHEST PRIORITY):
               - ALWAYS analyze conversation history FIRST before using document chunks
               - If the current question contains pronouns or references ("it", "this", "that", "the book", "the author", "who wrote it", etc.),
                 you MUST look at conversation history to identify what these refer to
               - Extract the relevant information from conversation history and include it explicitly in the prompt
               - Example: If user asks "who wrote it?" after asking "what is mindset?", look in conversation history
                 to find that "it" refers to "Mindset" book, and include that context explicitly
               - If conversation history contains the answer to the current question, prioritize that over document chunks
               - Maintain continuity - ensure follow-up questions are answered using context from previous messages
            
            1.5. INTERNET SEARCH INTEGRATION (CRITICAL - USE WHEN INFORMATION IS MISSING):
               - You have access to Google Search via the internet - USE IT whenever information is missing or incomplete
               - ALWAYS check if the question can be fully answered from documents/conversation history
               - If ANY part of the question cannot be answered from the context, instruct the answer-generating LLM to use Google Search
               - Use Google Search for:
                 * Industry benchmarks, market rates, salary comparisons, career advice
                 * General knowledge questions not covered in documents
                 * Current information, statistics, or recent developments
                 * Comparative analysis or external validation
                 * Questions completely out of scope (not related to uploaded documents)
                 * Any missing information needed to provide a complete answer
               - In your generated prompt, explicitly instruct the LLM to:
                 * Use Google Search to find missing information
                 * Clearly distinguish between document sources and internet sources
                 * Mention "According to internet search..." or "Based on current market data (sourced from internet)..." when using internet
               - Example: If asked "can he expect 40LPA+?", search for current salary trends for similar roles and experience
               - Example: If asked "is the pay justifiable?", search for industry benchmarks and include comparison data from internet
               - IMPORTANT: The system should act as a general AI assistant, not just exam prep - answer any question using internet when needed
            
            2. CONTEXT SYNTHESIS FROM DOCUMENT CHUNKS:
               - Only use document chunks if they are relevant to the current question
               - If the question is a follow-up that can be answered from conversation history, you may not need all chunks
               - Synthesize information from multiple document chunks into a coherent, unified context
               - Eliminate redundancy while preserving all critical information
               - Organize information logically (chronologically, by topic, or by importance)
               - Highlight key concepts, definitions, examples, and relationships
            
            3. ANSWER OPTIMIZATION:
               - Structure the prompt to guide answer generation appropriately
               - If marks are allocated, include instructions about answer length and depth based on marks
               - Emphasize citation requirements (which source/page/slide information comes from)
               - Ensure the prompt guides the LLM to use information from provided context AND internet search when needed
               - Include instructions for natural, conversational language (not overly formal unless required)
               - The system should act as a general AI assistant, answering any question using internet when information is missing
            
            4. QUESTION ANALYSIS:
               - Break down the question to identify what is being asked
               - Determine if this is a follow-up question requiring conversation history context
               - Determine the type of answer required (definition, explanation, comparison, analysis, etc.)
               - Identify key terms and concepts that need to be addressed
               - Ensure all aspects of the question are covered in the prompt
            
            5. PROMPT STRUCTURE:
               - Create a clear, well-organized prompt with distinct sections
               - Start with conversation history context (if relevant) - this is CRITICAL for follow-up questions
               - Then include context from document chunks (if needed)
               - Clearly state the question with resolved references
               - Include specific formatting and structure requirements
               - Add explicit instructions about citations, source attribution, and answer quality
            
            OUTPUT FORMAT:
            Create a complete, ready-to-use prompt that another LLM can directly use to generate a comprehensive answer.
            The prompt should be self-contained, clear, and comprehensive. Do not include meta-commentary about
            prompt creation - just create the actual prompt that will be used.
            
            IMPORTANT: 
            - If the question can be answered from conversation history alone, prioritize that over document chunks
            - If ANY information is missing or the question is out of scope, ALWAYS instruct the LLM to use Google Search
            - The system should act as a general AI assistant, not just exam prep - answer any question using internet when needed
            """;
        
        StringBuilder promptCreationUserPrompt = new StringBuilder();
        
        // Prioritize conversation history if available
        if (!conversationContext.isEmpty()) {
            promptCreationUserPrompt.append("=== CONVERSATION HISTORY (Last 10 messages from current chat) ===\n");
            promptCreationUserPrompt.append("CRITICAL: Analyze this FIRST. If the current question contains pronouns or references\n");
            promptCreationUserPrompt.append("(like 'it', 'this', 'that', 'the book', 'the author', 'who wrote it?'), you MUST look here\n");
            promptCreationUserPrompt.append("to understand what they refer to. Extract relevant information from this history.\n\n");
            promptCreationUserPrompt.append(conversationContext);
            promptCreationUserPrompt.append("\n=== END OF CONVERSATION HISTORY ===\n\n");
        }
        
        if (!retrievedContext.isEmpty()) {
            promptCreationUserPrompt.append("=== RETRIEVED DOCUMENT CHUNKS FROM VECTOR SEARCH ===\n");
            if (isFollowUpQuestion) {
                promptCreationUserPrompt.append("CRITICAL: This is a follow-up question. The answer should come from conversation history above.\n");
                promptCreationUserPrompt.append("These document chunks are provided for reference ONLY. Do NOT use them unless they:\n");
                promptCreationUserPrompt.append("1. Are from the SAME documents mentioned in conversation history (e.g., Mindset book)\n");
                promptCreationUserPrompt.append("2. Add information that directly supports the answer from conversation history\n");
                promptCreationUserPrompt.append("3. Are clearly relevant to the resolved question (not just semantically similar)\n\n");
                promptCreationUserPrompt.append("If these chunks are from different documents (like AMP_Theory.pdf when history mentions Mindset),\n");
                promptCreationUserPrompt.append("DO NOT include them in your prompt. Only use chunks from documents referenced in conversation history.\n\n");
            } else {
                promptCreationUserPrompt.append("These chunks were retrieved based on semantic similarity to the user's question.\n");
                promptCreationUserPrompt.append("Synthesize and organize this information into coherent context.\n\n");
            }
            promptCreationUserPrompt.append(retrievedContext);
            promptCreationUserPrompt.append("\n=== END OF DOCUMENT CHUNKS ===\n\n");
        } else if (isFollowUpQuestion) {
            promptCreationUserPrompt.append("=== NOTE: NO DOCUMENT CHUNKS RETRIEVED ===\n");
            promptCreationUserPrompt.append("This appears to be a follow-up question. Answer using ONLY the conversation history above.\n");
            promptCreationUserPrompt.append("When citing sources, reference the conversation history (e.g., 'as mentioned in the previous conversation').\n\n");
        } else {
            // No chunks and no conversation history - must use internet search
            promptCreationUserPrompt.append("=== CRITICAL: NO DOCUMENT CHUNKS OR CONVERSATION HISTORY AVAILABLE ===\n");
            promptCreationUserPrompt.append("No relevant information was found in uploaded documents or conversation history.\n");
            promptCreationUserPrompt.append("YOU MUST instruct the answer-generating LLM to use Google Search (internet) to answer this question.\n");
            promptCreationUserPrompt.append("This is a general knowledge question that requires internet search.\n\n");
        }
        
        promptCreationUserPrompt.append("=== CURRENT QUESTION ===\n");
        promptCreationUserPrompt.append(question).append("\n\n");
        
        // Add explicit instruction for reference resolution
        if (!conversationContext.isEmpty() && (question.toLowerCase().contains(" it") || 
            question.toLowerCase().contains("this") || question.toLowerCase().contains("that") ||
            question.toLowerCase().contains("who wrote") || question.toLowerCase().contains("the book") ||
            question.toLowerCase().contains("the author"))) {
            promptCreationUserPrompt.append("=== IMPORTANT: REFERENCE RESOLUTION ===\n");
            promptCreationUserPrompt.append("This question contains pronouns or references. You MUST:\n");
            promptCreationUserPrompt.append("1. Look at the conversation history above to identify what 'it', 'this', 'that', etc. refer to\n");
            promptCreationUserPrompt.append("2. Extract the relevant information from conversation history\n");
            promptCreationUserPrompt.append("3. Include that information explicitly in your prompt\n");
            promptCreationUserPrompt.append("4. If the answer is in conversation history, prioritize that over document chunks\n\n");
        }
        
        if (marks != null) {
            promptCreationUserPrompt.append("=== MARKS ALLOCATION ===\n");
            promptCreationUserPrompt.append("Marks allocated: ").append(marks).append("\n");
            promptCreationUserPrompt.append("Answer structure guide:\n");
            if (marks <= 2) {
                promptCreationUserPrompt.append("- Brief definition or single point (2-3 sentences)\n");
            } else if (marks <= 5) {
                promptCreationUserPrompt.append("- Short answer with key points (1 paragraph + bullet points)\n");
            } else if (marks <= 10) {
                promptCreationUserPrompt.append("- Detailed answer with introduction, main content, conclusion\n");
            } else {
                promptCreationUserPrompt.append("- Comprehensive essay-style with multiple sections\n");
            }
            promptCreationUserPrompt.append("\n");
        }
        
        if (customFormat != null && !customFormat.isBlank()) {
            promptCreationUserPrompt.append("=== ADDITIONAL FORMAT REQUIREMENTS ===\n");
            promptCreationUserPrompt.append(customFormat).append("\n\n");
        }
        
        promptCreationUserPrompt.append("=== YOUR TASK ===\n");
        if (isFollowUpQuestion) {
            promptCreationUserPrompt.append("THIS IS A FOLLOW-UP QUESTION WITH REFERENCES. You MUST:\n");
            promptCreationUserPrompt.append("1. FIRST identify what the pronouns/references refer to from conversation history\n");
            promptCreationUserPrompt.append("2. Extract the answer from conversation history (e.g., if asking 'who wrote it?', find the author from history)\n");
            promptCreationUserPrompt.append("3. Create a prompt that explicitly states the resolved question (e.g., 'Who wrote the Mindset book?')\n");
            promptCreationUserPrompt.append("4. Include the relevant information from conversation history in the prompt\n");
            promptCreationUserPrompt.append("5. CRITICAL CITATION RULES:\n");
            promptCreationUserPrompt.append("   - If document chunks are provided, check which documents they are from\n");
            promptCreationUserPrompt.append("   - ONLY use chunks from documents that were mentioned in conversation history\n");
            promptCreationUserPrompt.append("   - If conversation history mentions 'Mindset_by_Carol_S._Dweck.pdf', ONLY cite that document\n");
            promptCreationUserPrompt.append("   - DO NOT cite chunks from other documents (like AMP_Theory.pdf) even if they appear in the chunks list\n");
            promptCreationUserPrompt.append("   - If the answer comes entirely from conversation history, instruct the LLM to cite the conversation history\n");
            promptCreationUserPrompt.append("   - NEVER cite documents that were not mentioned in the conversation history\n");
            promptCreationUserPrompt.append("6. In your generated prompt, explicitly tell the answer-generating LLM which documents to cite\n\n");
        } else if (retrievedContext.isEmpty() && conversationContext.isEmpty()) {
            // No chunks and no conversation history - must use internet search
            promptCreationUserPrompt.append("CRITICAL: NO DOCUMENTS OR CONVERSATION HISTORY AVAILABLE.\n");
            promptCreationUserPrompt.append("You MUST create a prompt that instructs the answer-generating LLM to:\n");
            promptCreationUserPrompt.append("1. Use Google Search (internet) to find information about: ").append(question).append("\n");
            promptCreationUserPrompt.append("2. Search for general knowledge, biographical information, or any relevant details\n");
            promptCreationUserPrompt.append("3. Provide a comprehensive answer based on internet search results\n");
            promptCreationUserPrompt.append("4. Clearly state that the information comes from internet search\n");
            promptCreationUserPrompt.append("5. Maintain a natural, conversational tone\n");
            promptCreationUserPrompt.append("6. Do NOT mention document sources since none are available\n\n");
        } else {
            promptCreationUserPrompt.append("Create an optimized, comprehensive prompt that:\n");
            promptCreationUserPrompt.append("1. Synthesizes document chunks into coherent, well-organized context\n");
            promptCreationUserPrompt.append("2. Incorporates conversation history to maintain context (if available)\n");
            promptCreationUserPrompt.append("3. Clearly presents the current question\n");
            promptCreationUserPrompt.append("4. CRITICAL - INTERNET SEARCH INSTRUCTIONS:\n");
            promptCreationUserPrompt.append("   - Analyze if the question can be fully answered from documents/conversation history\n");
            promptCreationUserPrompt.append("   - If ANY information is missing or the question is out of scope, instruct the LLM to use Google Search\n");
            promptCreationUserPrompt.append("   - Specify what should be searched (e.g., 'salary trends for Java developers with 2 years experience', 'RAG and vector DB job market', '40 LPA salary expectations')\n");
            promptCreationUserPrompt.append("   - Tell the LLM to act as a general AI assistant - answer any question, not just exam prep\n");
            promptCreationUserPrompt.append("   - Ensure clear distinction: 'Information from documents: X' vs 'Information from internet search: Y'\n");
            promptCreationUserPrompt.append("   - Instruct to explicitly mention when using internet: 'According to internet search...' or 'Based on current market data (sourced from internet)...'\n");
            promptCreationUserPrompt.append("5. Includes instructions for answer structure, format, and citations\n");
            promptCreationUserPrompt.append("6. Guides the LLM to generate a comprehensive answer using documents AND internet search when needed\n");
            promptCreationUserPrompt.append("7. Ensures all aspects of the question are addressed\n");
            promptCreationUserPrompt.append("8. Maintains natural, conversational tone (not overly formal unless required)\n");
            promptCreationUserPrompt.append("9. Instructs the LLM to provide complete answers without truncation\n\n");
        }
        promptCreationUserPrompt.append("CRITICAL INSTRUCTIONS:\n");
        promptCreationUserPrompt.append("- If the question can be answered from conversation history, prioritize that information\n");
        promptCreationUserPrompt.append("- If ANY information is missing or the question is out of scope, ALWAYS instruct the LLM to use Google Search\n");
        promptCreationUserPrompt.append("- The system should act as a general AI assistant - answer any question using internet when needed\n");
        promptCreationUserPrompt.append("- Always distinguish between document sources and internet sources in citations\n");
        promptCreationUserPrompt.append("The prompt should be ready to use directly by another LLM to generate a comprehensive answer.");
        
        // Step 2.3: Call LLM to create optimized prompt
        // Enable Google Search for prompt creation so it can search for missing information
        log.info("Calling LLM to create optimized prompt (with Google Search enabled)...");
        String optimizedPrompt = geminiClient.generateContent(
            promptCreationUserPrompt.toString(), 
            promptCreationSystemPrompt,
            true  // Enable Google Search grounding
        );
        long promptCreationTime = System.currentTimeMillis() - startTime - embeddingTime - retrievalTime;
        
        log.info("=== STAGE 2 COMPLETE: Optimized prompt created ({}ms) ===", promptCreationTime);
        log.debug("Optimized prompt preview: {}", optimizedPrompt.substring(0, Math.min(200, optimizedPrompt.length())));
        
        // ============================================
        // STAGE 3: Second LLM Call - Generate Final Answer
        // ============================================
        log.info("=== STAGE 3: Second LLM Call - Generating Final Answer ===");
        
        String answerSystemPrompt = ExamAnswerPrompts.SYSTEM_PROMPT;
        log.info("Generating final answer using optimized prompt (with Google Search enabled)...");
        // Enable Google Search for final answer generation as well
        String answer = geminiClient.generateContent(optimizedPrompt, answerSystemPrompt, true);
        long answerGenerationTime = System.currentTimeMillis() - startTime - embeddingTime - retrievalTime - promptCreationTime;
        
        log.info("=== STAGE 3 COMPLETE: Final answer generated ({}ms) ===", answerGenerationTime);
        
        // Step 4: Build response - use filtered chunks for sources
        // Check if answer mentions internet search - if so, don't include document sources
        boolean answerFromInternet = answer.toLowerCase().contains("internet search") || 
                                     answer.toLowerCase().contains("via internet") ||
                                     answer.toLowerCase().contains("sourced from internet") ||
                                     answer.toLowerCase().contains("found via internet") ||
                                     answer.toLowerCase().contains("according to internet") ||
                                     answer.toLowerCase().contains("based on internet") ||
                                     answer.toLowerCase().contains("sourced from internet search") ||
                                     (filteredChunks.isEmpty() && !conversationContext.isEmpty() && 
                                      !answer.toLowerCase().contains("conversation history"));
        
        List<SourceInfo> sources = new ArrayList<>();
        
        // Only include document sources if answer didn't come primarily from internet
        if (!answerFromInternet) {
            sources = filteredChunks.stream()
                .map(chunk -> SourceInfo.builder()
                    .documentId(chunk.getDocument().getId())
                    .fileName(chunk.getDocument().getFileName())
                    .pageNumber(chunk.getPageNumber())
                    .slideNumber(chunk.getSlideNumber())
                    .excerpt(truncate(chunk.getContent(), 200))
                    .build())
                .collect(Collectors.toList());
        } else {
            log.info("Answer came from internet search - excluding document sources");
        }
        
        // For follow-up questions answered from conversation history, sources might be empty
        // In that case, we should indicate the answer came from conversation history
        if (isFollowUpWithReferences && sources.isEmpty() && !conversationContext.isEmpty()) {
            log.info("Follow-up question answered from conversation history - no document sources");
        }
        
        return RagResult.builder()
            .answer(answer)
            .sources(sources)
            .retrievalTimeMs(retrievalTime)
            .generationTimeMs(promptCreationTime + answerGenerationTime) // Total LLM time
            .chunksUsed(filteredChunks.size())
            .build();
    }
    
    private String buildContext(List<DocumentChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            sb.append(String.format(
                "[Source %d: %s, Page/Slide %d]\n%s\n\n",
                i + 1,
                chunk.getDocument().getFileName(),
                chunk.getSlideNumber() != null ? chunk.getSlideNumber() : chunk.getPageNumber(),
                chunk.getContent()
            ));
        }
        return sb.toString();
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
