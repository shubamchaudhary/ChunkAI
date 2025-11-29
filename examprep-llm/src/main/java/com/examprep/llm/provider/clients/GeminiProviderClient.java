package com.examprep.llm.provider.clients;

import com.examprep.llm.provider.LlmProvider;
import com.examprep.llm.provider.ProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class GeminiProviderClient implements ProviderClient {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public GeminiProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .baseUrl(LlmProvider.GEMINI.getBaseUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }
    
    @Override
    public String generateContent(String prompt, String apiKey) throws ProviderException {
        return generateContent(prompt, apiKey, LlmProvider.GEMINI.getDefaultModel());
    }
    
    @Override
    public String generateContent(String prompt, String apiKey, String model) throws ProviderException {
        long startTime = System.currentTimeMillis();
        String effectiveModel = model != null ? model : LlmProvider.GEMINI.getDefaultModel();
        
        log.info("[GEMINI] Starting content generation | model={} | promptLength={}", effectiveModel, prompt.length());
        
        Map<String, Object> request = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", prompt)
                ))
            ),
            "generationConfig", Map.of(
                "maxOutputTokens", 2048,
                "temperature", 0.3
            )
        );
        
        try {
            String url = "/" + effectiveModel + ":generateContent?key=" + apiKey;
            log.debug("[GEMINI] Sending request | model={} | url={}", effectiveModel, url);
            
            String response = webClient.post()
                .uri(url)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();
            
            long duration = System.currentTimeMillis() - startTime;
            log.debug("[GEMINI] Received response | model={} | durationMs={} | responseLength={}", 
                effectiveModel, duration, response != null ? response.length() : 0);
            
            String content = extractContent(response);
            long totalDuration = System.currentTimeMillis() - startTime;
            
            log.info("[GEMINI] Content generated successfully | model={} | durationMs={} | responseLength={}", 
                effectiveModel, totalDuration, content.length());
            
            return content;
            
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[GEMINI] HTTP error | model={} | statusCode={} | statusText={} | durationMs={} | error={}", 
                effectiveModel, e.getStatusCode().value(), e.getStatusText(), duration, e.getMessage());
            throw mapException(e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[GEMINI] Request failed | model={} | durationMs={} | error={}", 
                effectiveModel, duration, e.getMessage(), e);
            throw new ProviderException(
                "Gemini request failed: " + e.getMessage(),
                LlmProvider.GEMINI, 500, true, e
            );
        }
    }
    
    private String extractContent(String response) throws ProviderException {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("candidates")
                .get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText();
        } catch (Exception e) {
            throw new ProviderException(
                "Failed to parse Gemini response",
                LlmProvider.GEMINI, 500, false, e
            );
        }
    }
    
    private ProviderException mapException(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        boolean retryable = status == 429 || status >= 500;
        String message = String.format("Gemini API error: %d %s", status, e.getStatusText());
        
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString());
            if (error.has("error") && error.get("error").has("message")) {
                message = error.get("error").get("message").asText();
            }
        } catch (Exception ignored) {}
        
        return new ProviderException(message, LlmProvider.GEMINI, status, retryable, e);
    }
    
    @Override
    public LlmProvider getProvider() {
        return LlmProvider.GEMINI;
    }
    
    @Override
    public String getDefaultModel() {
        return LlmProvider.GEMINI.getDefaultModel();
    }
}

