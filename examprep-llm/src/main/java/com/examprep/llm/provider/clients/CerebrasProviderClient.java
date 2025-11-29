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
public class CerebrasProviderClient implements ProviderClient {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public CerebrasProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .baseUrl(LlmProvider.CEREBRAS.getBaseUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }
    
    @Override
    public String generateContent(String prompt, String apiKey) throws ProviderException {
        return generateContent(prompt, apiKey, LlmProvider.CEREBRAS.getDefaultModel());
    }
    
    @Override
    public String generateContent(String prompt, String apiKey, String model) throws ProviderException {
        long startTime = System.currentTimeMillis();
        String effectiveModel = model != null ? model : LlmProvider.CEREBRAS.getDefaultModel();
        
        log.info("[CEREBRAS] Starting content generation | model={} | promptLength={}", effectiveModel, prompt.length());
        
        Map<String, Object> request = Map.of(
            "model", effectiveModel,
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", 2048,
            "temperature", 0.3
        );
        
        try {
            log.debug("[CEREBRAS] Sending request | model={} | url={}", effectiveModel, LlmProvider.CEREBRAS.getBaseUrl());
            
            String response = webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();
            
            long duration = System.currentTimeMillis() - startTime;
            log.debug("[CEREBRAS] Received response | model={} | durationMs={} | responseLength={}", 
                effectiveModel, duration, response != null ? response.length() : 0);
            
            String content = extractContent(response);
            long totalDuration = System.currentTimeMillis() - startTime;
            
            log.info("[CEREBRAS] Content generated successfully | model={} | durationMs={} | responseLength={}", 
                effectiveModel, totalDuration, content.length());
            
            return content;
            
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[CEREBRAS] HTTP error | model={} | statusCode={} | statusText={} | durationMs={} | error={}", 
                effectiveModel, e.getStatusCode().value(), e.getStatusText(), duration, e.getMessage());
            throw mapException(e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[CEREBRAS] Request failed | model={} | durationMs={} | error={}", 
                effectiveModel, duration, e.getMessage(), e);
            throw new ProviderException(
                "Cerebras request failed: " + e.getMessage(),
                LlmProvider.CEREBRAS, 500, true, e
            );
        }
    }
    
    private String extractContent(String response) throws ProviderException {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new ProviderException(
                "Failed to parse Cerebras response",
                LlmProvider.CEREBRAS, 500, false, e
            );
        }
    }
    
    private ProviderException mapException(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        boolean retryable = status == 429 || status >= 500;
        String message = String.format("Cerebras API error: %d %s", status, e.getStatusText());
        
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString());
            if (error.has("error") && error.get("error").has("message")) {
                message = error.get("error").get("message").asText();
            }
        } catch (Exception ignored) {}
        
        return new ProviderException(message, LlmProvider.CEREBRAS, status, retryable, e);
    }
    
    @Override
    public LlmProvider getProvider() {
        return LlmProvider.CEREBRAS;
    }
    
    @Override
    public String getDefaultModel() {
        return LlmProvider.CEREBRAS.getDefaultModel();
    }
}

