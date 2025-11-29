package com.examprep.llm.client;

import com.examprep.llm.model.EmbeddingResponse;
import com.examprep.llm.model.GenerationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {
    
    private final GeminiConfig config;
    private final WebClient.Builder webClientBuilder;
    
    /**
     * Generate embeddings for text using text-embedding-004
     */
    public float[] generateEmbedding(String text) {
        return generateEmbedding(text, null);
    }
    
    /**
     * Generate embeddings for text using text-embedding-004 with optional API key
     */
    public float[] generateEmbedding(String text, String apiKey) {
        String keyToUse = apiKey != null ? apiKey : 
            (config.getApiKey() != null ? config.getApiKey() : 
             (config.getAllApiKeys().isEmpty() ? null : config.getAllApiKeys().get(0)));
        
        if (keyToUse == null || keyToUse.isEmpty()) {
            throw new RuntimeException("Gemini API key is not set. Please set GEMINI_API_KEY environment variable or gemini.api-key in application.properties");
        }
        
        String url = String.format(
            "%s/models/%s:embedContent?key=%s",
            config.getBaseUrl(),
            config.getEmbeddingModel(),
            keyToUse
        );
        
        log.debug("Generating embedding using URL: {} (API key length: {})", 
            url.replace(keyToUse, "***"), 
            keyToUse.length());
        
        Map<String, Object> request = Map.of(
            "model", "models/" + config.getEmbeddingModel(),
            "content", Map.of(
                "parts", List.of(Map.of("text", text))
            )
        );
        
        // Retry logic for connection errors
        int maxRetries = 3;
        long baseDelayMs = 1000; // Start with 1 second
        Exception lastException = null;
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                EmbeddingResponse response = webClientBuilder.build()
                    .post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .block();
                
                if (response == null || response.getEmbedding() == null) {
                    throw new RuntimeException("Failed to generate embedding: response was null or empty");
                }
                
                return response.getEmbedding().getValues();
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                String responseBody = e.getResponseBodyAsString();
                log.error("Gemini API error: Status={}, Response={}", e.getStatusCode(), responseBody);
                if (e.getStatusCode().value() == 403) {
                    // Check if it's a leaked key error
                    if (responseBody != null && responseBody.contains("leaked")) {
                        throw new RuntimeException(
                            "API key was reported as leaked. Please use another API key. Error: " + responseBody, e);
                    }
                    throw new RuntimeException(
                        "Gemini API authentication failed (403 Forbidden). " +
                        "Please check: 1) API key is valid, 2) API key has access to embedding models, " +
                        "3) API key is not expired. Error: " + responseBody, e);
                }
                // Don't retry on HTTP errors (except 429 rate limit)
                if (e.getStatusCode().value() != 429 && e.getStatusCode().value() < 500) {
                    throw new RuntimeException("Failed to generate embedding: " + e.getMessage(), e);
                }
                lastException = e;
            } catch (org.springframework.web.reactive.function.client.WebClientRequestException e) {
                // Connection errors - retry with exponential backoff
                lastException = e;
                if (attempt < maxRetries - 1) {
                    long delayMs = baseDelayMs * (1L << attempt); // Exponential backoff: 1s, 2s, 4s
                    log.warn("Connection error during embedding generation (attempt {}/{}), retrying in {}ms: {}", 
                        attempt + 1, maxRetries, delayMs, e.getMessage());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry delay", ie);
                    }
                } else {
                    log.error("Connection error after {} attempts: {}", maxRetries, e.getMessage());
                }
            } catch (Exception e) {
                // Other errors - don't retry
                throw new RuntimeException("Failed to generate embedding: " + e.getMessage(), e);
            }
        }
        
        // If we exhausted retries, throw the last exception
        throw new RuntimeException("Failed to generate embedding after " + maxRetries + " attempts: " + 
            (lastException != null ? lastException.getMessage() : "Unknown error"), lastException);
    }
    
    /**
     * Generate text using Gemini Flash
     */
    public String generateContent(String prompt, String systemInstruction) {
        return generateContent(prompt, systemInstruction, false, null);
    }
    
    /**
     * Generate text using Gemini Flash with optional Google Search grounding
     * @param prompt The user prompt
     * @param systemInstruction The system instruction
     * @param useGoogleSearch If true, enables Google Search grounding for internet access
     */
    public String generateContent(String prompt, String systemInstruction, boolean useGoogleSearch) {
        return generateContent(prompt, systemInstruction, useGoogleSearch, null);
    }
    
    /**
     * Generate text using Gemini Flash with optional Google Search grounding and specific API key
     * @param prompt The user prompt
     * @param systemInstruction The system instruction
     * @param useGoogleSearch If true, enables Google Search grounding for internet access
     * @param apiKey Optional API key to use (if null, uses default from config)
     */
    public String generateContent(String prompt, String systemInstruction, boolean useGoogleSearch, String apiKey) {
        return generateContent(prompt, systemInstruction, useGoogleSearch, apiKey, null);
    }
    
    /**
     * Generate text using Gemini Flash with optional Google Search grounding, specific API key, and custom maxOutputTokens
     * @param prompt The user prompt
     * @param systemInstruction The system instruction
     * @param useGoogleSearch If true, enables Google Search grounding for internet access
     * @param apiKey Optional API key to use (if null, uses default from config)
     * @param maxOutputTokens Optional max output tokens (if null, uses config default)
     */
    public String generateContent(String prompt, String systemInstruction, boolean useGoogleSearch, String apiKey, Integer maxOutputTokens) {
        String keyToUse = apiKey != null ? apiKey : 
            (config.getApiKey() != null ? config.getApiKey() : 
             (config.getAllApiKeys().isEmpty() ? null : config.getAllApiKeys().get(0)));
        
        if (keyToUse == null || keyToUse.isEmpty()) {
            throw new RuntimeException("Gemini API key is not set. Please set GEMINI_API_KEY environment variable or gemini.api-key in application.properties");
        }
        
        String url = String.format(
            "%s/models/%s:generateContent?key=%s",
            config.getBaseUrl(),
            config.getGenerationModel(),
            keyToUse
        );
        
        java.util.Map<String, Object> requestMap = new java.util.HashMap<>();
        requestMap.put("contents", List.of(
            Map.of("parts", List.of(Map.of("text", prompt)))
        ));
        requestMap.put("systemInstruction", Map.of(
            "parts", List.of(Map.of("text", systemInstruction))
        ));
        int outputTokens = maxOutputTokens != null ? maxOutputTokens : config.getMaxOutputTokens();
        requestMap.put("generationConfig", Map.of(
            "temperature", 0.7,
            "topP", 0.95,
            "maxOutputTokens", outputTokens  // Configurable max output tokens
        ));
        
        // Enable Google Search grounding if requested
        if (useGoogleSearch) {
            requestMap.put("tools", List.of(
                Map.of("googleSearch", Map.of())
            ));
            log.info("Google Search grounding enabled for this request");
        }
        
        Map<String, Object> request = Map.copyOf(requestMap);
        
        try {
            GenerationResponse response = webClientBuilder.build()
                .post()
                .uri(url)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GenerationResponse.class)
                .block();
            
            if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
                throw new RuntimeException("Failed to generate content: response was null or empty");
            }
            
            GenerationResponse.Candidate candidate = response.getCandidates().get(0);
            
            // Check finish reason first
            String finishReason = candidate.getFinishReason();
            if (finishReason != null) {
                if (finishReason.equals("SAFETY")) {
                    String safetyMessage = candidate.getFinishMessage() != null ? candidate.getFinishMessage() : "Content was blocked by safety filters";
                    log.error("Gemini response blocked by safety filters: {}", safetyMessage);
                    throw new RuntimeException("Content generation blocked by safety filters: " + safetyMessage);
                } else if (finishReason.equals("RECITATION")) {
                    log.error("Gemini response blocked due to recitation");
                    throw new RuntimeException("Content generation blocked due to recitation concerns");
                } else if (!finishReason.equals("STOP") && !finishReason.equals("MAX_TOKENS")) {
                    log.warn("Gemini response finished with unexpected reason: {} (message: {})", 
                        finishReason, candidate.getFinishMessage());
                }
            }
            
            // Check if content and parts exist
            if (candidate.getContent() == null) {
                throw new RuntimeException("Failed to generate content: candidate content is null. Finish reason: " + finishReason);
            }
            
            if (candidate.getContent().getParts() == null || candidate.getContent().getParts().isEmpty()) {
                String errorMsg = "Failed to generate content: no parts in response. Finish reason: " + finishReason;
                if (candidate.getFinishMessage() != null) {
                    errorMsg += ", Message: " + candidate.getFinishMessage();
                }
                log.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
            
            String text = candidate.getContent().getParts().get(0).getText();
            
            if (text == null || text.isEmpty()) {
                throw new RuntimeException("Failed to generate content: text is null or empty. Finish reason: " + finishReason);
            }
            
            // Check if response was truncated
            if (finishReason != null && finishReason.equals("MAX_TOKENS")) {
                log.warn("Gemini response was truncated due to MAX_TOKENS limit. Response length: {}. Consider increasing maxOutputTokens.", 
                    text.length());
            }
            
            log.debug("Generated content length: {} characters. Finish reason: {}. Google Search: {}", 
                text.length(), finishReason, useGoogleSearch);
            
            return text;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Gemini API error: Status={}, URL={}, Response={}", 
                e.getStatusCode(), 
                url.replace(keyToUse, "***"), 
                e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 404) {
                throw new RuntimeException(
                    "Gemini model not found (404). Model: " + config.getGenerationModel() + 
                    ". Please check: 1) Model name is correct, 2) API key has access to this model. " +
                    "Error: " + e.getResponseBodyAsString(), e);
            }
            throw new RuntimeException("Failed to generate content: " + e.getMessage(), e);
        }
    }
}

