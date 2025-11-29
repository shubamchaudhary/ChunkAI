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
        return generateEmbedding(text, config.getApiKey());
    }
    
    /**
     * Generate embeddings for text using text-embedding-004 with specific API key
     */
    public float[] generateEmbedding(String text, String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("Gemini API key is not set. Please set GEMINI_API_KEY environment variable or gemini.api-key in application.properties");
        }
        
        String url = String.format(
            "%s/models/%s:embedContent?key=%s",
            config.getBaseUrl(),
            config.getEmbeddingModel(),
            apiKey
        );
        
        log.debug("Generating embedding using URL: {} (API key length: {})", 
            url.replace(config.getApiKey(), "***"), 
            config.getApiKey().length());
        
        Map<String, Object> request = Map.of(
            "model", "models/" + config.getEmbeddingModel(),
            "content", Map.of(
                "parts", List.of(Map.of("text", text))
            )
        );
        
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
            log.error("Gemini API error: Status={}, Response={}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 403) {
                throw new RuntimeException(
                    "Gemini API authentication failed (403 Forbidden). " +
                    "Please check: 1) API key is valid, 2) API key has access to embedding models, " +
                    "3) API key is not expired. Error: " + e.getResponseBodyAsString(), e);
            }
            throw new RuntimeException("Failed to generate embedding: " + e.getMessage(), e);
        }
    }
    
    /**
     * Generate text using Gemini Flash
     */
    public String generateContent(String prompt, String systemInstruction) {
        return generateContent(prompt, systemInstruction, false);
    }
    
    /**
     * Generate text using Gemini Flash with optional Google Search grounding
     * @param prompt The user prompt
     * @param systemInstruction The system instruction
     * @param useGoogleSearch If true, enables Google Search grounding for internet access
     */
    public String generateContent(String prompt, String systemInstruction, boolean useGoogleSearch) {
        return generateContent(prompt, systemInstruction, useGoogleSearch, config.getApiKey());
    }
    
    /**
     * Generate text using Gemini Flash with API key from lease.
     * @param prompt The user prompt
     * @param systemInstruction The system instruction
     * @param useGoogleSearch If true, enables Google Search grounding for internet access
     * @param apiKey The API key to use (from ApiKeyManager lease)
     */
    public String generateContent(String prompt, String systemInstruction, boolean useGoogleSearch, String apiKey) {
        String url = String.format(
            "%s/models/%s:generateContent?key=%s",
            config.getBaseUrl(),
            config.getGenerationModel(),
            apiKey
        );
        
        java.util.Map<String, Object> requestMap = new java.util.HashMap<>();
        requestMap.put("contents", List.of(
            Map.of("parts", List.of(Map.of("text", prompt)))
        ));
        requestMap.put("systemInstruction", Map.of(
            "parts", List.of(Map.of("text", systemInstruction))
        ));
        requestMap.put("generationConfig", Map.of(
            "temperature", 0.7,
            "topP", 0.95,
            "maxOutputTokens", config.getMaxOutputTokens()  // Configurable max output tokens
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
            String text = candidate.getContent().getParts().get(0).getText();
            
            // Check if response was truncated
            String finishReason = candidate.getFinishReason();
            if (finishReason != null && !finishReason.equals("STOP")) {
                log.warn("Gemini response finished with reason: {} (message: {}). Response may be truncated.", 
                    finishReason, candidate.getFinishMessage());
                if (finishReason.equals("MAX_TOKENS")) {
                    log.error("Response was truncated due to MAX_TOKENS limit. Consider increasing maxOutputTokens.");
                }
            }
            
            log.debug("Generated content length: {} characters. Finish reason: {}. Google Search: {}", 
                text.length(), finishReason, useGoogleSearch);
            
            return text;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Gemini API error: Status={}, URL={}, Response={}", 
                e.getStatusCode(), 
                url.replace(apiKey, "***"), 
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

