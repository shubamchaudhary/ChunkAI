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
public class SambanovaProviderClient implements ProviderClient {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public SambanovaProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .baseUrl(LlmProvider.SAMBANOVA.getBaseUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }
    
    @Override
    public String generateContent(String prompt, String apiKey) throws ProviderException {
        return generateContent(prompt, apiKey, LlmProvider.SAMBANOVA.getDefaultModel());
    }
    
    @Override
    public String generateContent(String prompt, String apiKey, String model) throws ProviderException {
        long startTime = System.currentTimeMillis();
        String effectiveModel = model != null ? model : LlmProvider.SAMBANOVA.getDefaultModel();
        
        log.info("[SAMBANOVA] Starting content generation | model={} | promptLength={}", effectiveModel, prompt.length());
        
        Map<String, Object> request = Map.of(
            "model", effectiveModel,
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", 2048,
            "temperature", 0.3
        );
        
        try {
            log.debug("[SAMBANOVA] Sending request | model={} | url={}", effectiveModel, LlmProvider.SAMBANOVA.getBaseUrl());
            
            String response = webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(90))
                .block();
            
            long duration = System.currentTimeMillis() - startTime;
            log.debug("[SAMBANOVA] Received response | model={} | durationMs={} | responseLength={}", 
                effectiveModel, duration, response != null ? response.length() : 0);
            
            String content = extractContent(response);
            long totalDuration = System.currentTimeMillis() - startTime;
            
            log.info("[SAMBANOVA] Content generated successfully | model={} | durationMs={} | responseLength={}", 
                effectiveModel, totalDuration, content.length());
            
            return content;
            
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[SAMBANOVA] HTTP error | model={} | statusCode={} | statusText={} | durationMs={} | error={}", 
                effectiveModel, e.getStatusCode().value(), e.getStatusText(), duration, e.getMessage());
            throw mapException(e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[SAMBANOVA] Request failed | model={} | durationMs={} | error={}", 
                effectiveModel, duration, e.getMessage(), e);
            throw new ProviderException(
                "SambaNova request failed: " + e.getMessage(),
                LlmProvider.SAMBANOVA, 500, true, e
            );
        }
    }
    
    private String extractContent(String response) throws ProviderException {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new ProviderException(
                "Failed to parse SambaNova response",
                LlmProvider.SAMBANOVA, 500, false, e
            );
        }
    }
    
    private ProviderException mapException(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        // 410 (Gone) means service/endpoint is deprecated or unavailable - not retryable
        // 413 (Payload Too Large) is also not retryable
        // 429 (Rate Limit) and 5xx (Server Errors) are retryable
        boolean retryable = status == 429 || status >= 500;
        String message = String.format("SambaNova API error: %d %s", status, e.getStatusText());
        
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString());
            if (error.has("error") && error.get("error").has("message")) {
                message = error.get("error").get("message").asText();
            }
        } catch (Exception ignored) {}
        
        // Log specific error for 410 (Gone) - endpoint may be deprecated
        if (status == 410) {
            log.warn("[SAMBANOVA] Service unavailable (410 Gone) - skipping to next provider | statusCode=410 | message={}", message);
        }
        
        return new ProviderException(message, LlmProvider.SAMBANOVA, status, retryable, e);
    }
    
    @Override
    public LlmProvider getProvider() {
        return LlmProvider.SAMBANOVA;
    }
    
    @Override
    public String getDefaultModel() {
        return LlmProvider.SAMBANOVA.getDefaultModel();
    }
}

