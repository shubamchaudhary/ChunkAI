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
public class GroqProviderClient implements ProviderClient {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public GroqProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .baseUrl(LlmProvider.GROQ.getBaseUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }
    
    @Override
    public String generateContent(String prompt, String apiKey) throws ProviderException {
        return generateContent(prompt, apiKey, LlmProvider.GROQ.getDefaultModel());
    }
    
    @Override
    public String generateContent(String prompt, String apiKey, String model) throws ProviderException {
        long startTime = System.currentTimeMillis();
        String effectiveModel = model != null ? model : LlmProvider.GROQ.getDefaultModel();
        
        log.info("[GROQ] Starting content generation | model={} | promptLength={}", effectiveModel, prompt.length());
        
        Map<String, Object> request = Map.of(
            "model", effectiveModel,
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", 2048,
            "temperature", 0.3
        );
        
        try {
            log.debug("[GROQ] Sending request | model={} | url={}", effectiveModel, LlmProvider.GROQ.getBaseUrl());
            
            String response = webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();
            
            long duration = System.currentTimeMillis() - startTime;
            log.debug("[GROQ] Received response | model={} | durationMs={} | responseLength={}", 
                effectiveModel, duration, response != null ? response.length() : 0);
            
            String content = extractContent(response);
            long totalDuration = System.currentTimeMillis() - startTime;
            
            log.info("[GROQ] Content generated successfully | model={} | durationMs={} | responseLength={}", 
                effectiveModel, totalDuration, content.length());
            
            return content;
            
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[GROQ] HTTP error | model={} | statusCode={} | statusText={} | durationMs={} | error={}", 
                effectiveModel, e.getStatusCode().value(), e.getStatusText(), duration, e.getMessage());
            throw mapException(e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[GROQ] Request failed | model={} | durationMs={} | error={}", 
                effectiveModel, duration, e.getMessage(), e);
            throw new ProviderException(
                "Groq request failed: " + e.getMessage(),
                LlmProvider.GROQ, 500, true, e
            );
        }
    }
    
    private String extractContent(String response) throws ProviderException {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new ProviderException(
                "Failed to parse Groq response",
                LlmProvider.GROQ, 500, false, e
            );
        }
    }
    
    private ProviderException mapException(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        // 413 (Payload Too Large) and 410 (Gone) are not retryable - skip to next provider
        // 429 (Rate Limit) and 5xx (Server Errors) are retryable
        boolean retryable = status == 429 || status >= 500;
        String message = String.format("Groq API error: %d %s", status, e.getStatusText());
        
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString());
            if (error.has("error") && error.get("error").has("message")) {
                message = error.get("error").get("message").asText();
            }
        } catch (Exception ignored) {}
        
        // Log specific error for 413 (Payload Too Large)
        if (status == 413) {
            log.warn("[GROQ] Request too large - skipping to next provider | statusCode=413 | message={}", message);
        }
        
        return new ProviderException(message, LlmProvider.GROQ, status, retryable, e);
    }
    
    @Override
    public LlmProvider getProvider() {
        return LlmProvider.GROQ;
    }
    
    @Override
    public String getDefaultModel() {
        return LlmProvider.GROQ.getDefaultModel();
    }
}

