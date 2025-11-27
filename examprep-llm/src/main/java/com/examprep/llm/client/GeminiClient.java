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
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new RuntimeException("Gemini API key is not set. Please set GEMINI_API_KEY environment variable or gemini.api-key in application.properties");
        }
        
        String url = String.format(
            "%s/models/%s:embedContent?key=%s",
            config.getBaseUrl(),
            config.getEmbeddingModel(),
            config.getApiKey()
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
        String url = String.format(
            "%s/models/%s:generateContent?key=%s",
            config.getBaseUrl(),
            config.getGenerationModel(),
            config.getApiKey()
        );
        
        Map<String, Object> request = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
            ),
            "systemInstruction", Map.of(
                "parts", List.of(Map.of("text", systemInstruction))
            ),
            "generationConfig", Map.of(
                "temperature", 0.7,
                "topP", 0.95,
                "maxOutputTokens", 8192
            )
        );
        
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
            
            return response.getCandidates().get(0).getContent().getParts().get(0).getText();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Gemini API error: Status={}, URL={}, Response={}", 
                e.getStatusCode(), 
                url.replace(config.getApiKey(), "***"), 
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

