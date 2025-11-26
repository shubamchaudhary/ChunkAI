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
        String url = String.format(
            "%s/models/%s:embedContent?key=%s",
            config.getBaseUrl(),
            config.getEmbeddingModel(),
            config.getApiKey()
        );
        
        Map<String, Object> request = Map.of(
            "model", "models/" + config.getEmbeddingModel(),
            "content", Map.of(
                "parts", List.of(Map.of("text", text))
            )
        );
        
        EmbeddingResponse response = webClientBuilder.build()
            .post()
            .uri(url)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(EmbeddingResponse.class)
            .block();
        
        if (response == null || response.getEmbedding() == null) {
            throw new RuntimeException("Failed to generate embedding");
        }
        
        return response.getEmbedding().getValues();
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
        
        GenerationResponse response = webClientBuilder.build()
            .post()
            .uri(url)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(GenerationResponse.class)
            .block();
        
        if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
            throw new RuntimeException("Failed to generate content");
        }
        
        return response.getCandidates().get(0).getContent().getParts().get(0).getText();
    }
}

