package com.examprep.llm.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Service for generating embeddings using Gemini's batch embedding API.
 * Supports up to 100 texts per batch call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiBatchEmbeddingService {
    
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    
    @Value("${gemini.embedding.api-key:${gemini.api-key:}}")
    private String apiKey;
    
    @Value("${gemini.embedding.model:text-embedding-004}")
    private String embeddingModel;
    
    @Value("${gemini.embedding.batch-size:100}")
    private int batchSize;
    
    @Value("${gemini.embedding.rate-limit-rpm:100}")
    private int rateLimitRpm;
    
    @Value("${gemini.embedding.retry-attempts:3}")
    private int retryAttempts;
    
    @Value("${gemini.embedding.retry-delay-ms:1000}")
    private long retryDelayMs;
    
    @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String baseUrl;
    
    // Semaphore for rate limiting (100 RPM = ~1 request per 600ms)
    private final Semaphore rateLimiter = new Semaphore(1, true);
    private volatile long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL_MS = 600; // 600ms = 100 requests per minute
    
    /**
     * Generate embeddings for a batch of texts (up to 100 texts per call).
     * Automatically splits into multiple batches if needed.
     * 
     * @param texts List of texts to embed (can be > 100)
     * @return List of embeddings in same order as input texts
     */
    public List<float[]> generateBatchEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        
        long startTime = System.currentTimeMillis();
        String batchId = "batch-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        
        log.info("[BATCH_EMBED] Starting batch embedding | batchId={} | totalTexts={} | batchSize={}", 
            batchId, texts.size(), batchSize);
        
        List<float[]> allEmbeddings = new ArrayList<>();
        
        // Split into batches of max batchSize
        for (int i = 0; i < texts.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, endIndex);
            
            log.info("[BATCH_EMBED] Processing batch | batchId={} | batchNumber={} | textsInBatch={} | startIndex={} | endIndex={}", 
                batchId, (i / batchSize) + 1, batch.size(), i, endIndex);
            
            List<float[]> batchEmbeddings = generateSingleBatch(batch, batchId, (i / batchSize) + 1);
            allEmbeddings.addAll(batchEmbeddings);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[BATCH_EMBED] Batch embedding completed | batchId={} | totalTexts={} | totalBatches={} | durationMs={} | avgTimePerTextMs={}", 
            batchId, texts.size(), (texts.size() + batchSize - 1) / batchSize, duration,
            texts.isEmpty() ? 0 : duration / texts.size());
        
        return allEmbeddings;
    }
    
    /**
     * Generate embeddings for a single batch (max 100 texts).
     */
    private List<float[]> generateSingleBatch(List<String> texts, String batchId, int batchNumber) {
        // Respect rate limit
        waitForRateLimit();
        
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < retryAttempts) {
            attempt++;
            long batchStartTime = System.currentTimeMillis();
            
            try {
                log.debug("[BATCH_EMBED] Calling Gemini batch API | batchId={} | batchNumber={} | attempt={}/{} | textsCount={}", 
                    batchId, batchNumber, attempt, retryAttempts, texts.size());
                
                List<float[]> embeddings = callGeminiBatchApi(texts);
                
                long batchDuration = System.currentTimeMillis() - batchStartTime;
                log.info("[BATCH_EMBED] Batch API call succeeded | batchId={} | batchNumber={} | attempt={} | textsCount={} | durationMs={}", 
                    batchId, batchNumber, attempt, texts.size(), batchDuration);
                
                return embeddings;
                
            } catch (WebClientResponseException e) {
                lastException = e;
                long batchDuration = System.currentTimeMillis() - batchStartTime;
                
                if (e.getStatusCode().value() == 429 || e.getStatusCode().value() >= 500) {
                    // Retryable error
                    log.warn("[BATCH_EMBED] Batch API call failed (retryable) | batchId={} | batchNumber={} | attempt={}/{} | statusCode={} | durationMs={} | error={}", 
                        batchId, batchNumber, attempt, retryAttempts, e.getStatusCode().value(), batchDuration, e.getMessage());
                    
                    if (attempt < retryAttempts) {
                        try {
                            Thread.sleep(retryDelayMs * attempt); // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted during retry", ie);
                        }
                        continue;
                    }
                } else {
                    // Non-retryable error
                    log.error("[BATCH_EMBED] Batch API call failed (non-retryable) | batchId={} | batchNumber={} | attempt={} | statusCode={} | durationMs={} | error={}", 
                        batchId, batchNumber, attempt, e.getStatusCode().value(), batchDuration, e.getMessage());
                    throw new RuntimeException("Gemini batch embedding failed: " + e.getMessage(), e);
                }
                
            } catch (Exception e) {
                lastException = e;
                log.error("[BATCH_EMBED] Batch API call failed | batchId={} | batchNumber={} | attempt={}/{} | error={}", 
                    batchId, batchNumber, attempt, retryAttempts, e.getMessage(), e);
                
                if (attempt < retryAttempts) {
                    try {
                        Thread.sleep(retryDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                    continue;
                }
            }
        }
        
        throw new RuntimeException("Failed to generate batch embeddings after " + retryAttempts + " attempts", lastException);
    }
    
    /**
     * Call Gemini batch embedding API.
     */
    private List<float[]> callGeminiBatchApi(List<String> texts) {
        String url = String.format("%s/models/%s:batchEmbedContents?key=%s", baseUrl, embeddingModel, apiKey);
        
        // Build request: list of embedding requests
        List<Map<String, Object>> requests = new ArrayList<>();
        for (String text : texts) {
            requests.add(Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of(
                    "parts", List.of(Map.of("text", text))
                )
            ));
        }
        
        Map<String, Object> requestBody = Map.of("requests", requests);
        
        try {
            String response = webClientBuilder.build()
                .post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(120)) // Longer timeout for batch requests
                .block();
            
            if (response == null || response.isEmpty()) {
                throw new RuntimeException("Empty response from Gemini batch embedding API");
            }
            
            // Parse response
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddingsNode = root.path("embeddings");
            
            if (!embeddingsNode.isArray()) {
                throw new RuntimeException("Invalid response format: embeddings is not an array");
            }
            
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode embeddingNode : embeddingsNode) {
                JsonNode valuesNode = embeddingNode.path("values");
                if (!valuesNode.isArray()) {
                    throw new RuntimeException("Invalid response format: embedding values is not an array");
                }
                
                float[] embedding = new float[valuesNode.size()];
                for (int i = 0; i < valuesNode.size(); i++) {
                    embedding[i] = (float) valuesNode.get(i).asDouble();
                }
                embeddings.add(embedding);
            }
            
            if (embeddings.size() != texts.size()) {
                throw new RuntimeException(
                    String.format("Embedding count mismatch: expected %d, got %d", texts.size(), embeddings.size())
                );
            }
            
            return embeddings;
            
        } catch (WebClientResponseException e) {
            log.error("[BATCH_EMBED] Gemini API HTTP error | statusCode={} | statusText={} | error={}", 
                e.getStatusCode().value(), e.getStatusText(), e.getResponseBodyAsString());
            throw e;
        } catch (Exception e) {
            log.error("[BATCH_EMBED] Failed to parse Gemini batch embedding response", e);
            throw new RuntimeException("Failed to parse batch embedding response: " + e.getMessage(), e);
        }
    }
    
    /**
     * Wait for rate limit (conservative: 100 RPM = 1 request per 600ms).
     */
    private void waitForRateLimit() {
        try {
            rateLimiter.acquire();
            try {
                long currentTime = System.currentTimeMillis();
                long timeSinceLastRequest = currentTime - lastRequestTime;
                
                if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
                    long waitTime = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest;
                    log.debug("[BATCH_EMBED] Rate limiting: waiting {}ms", waitTime);
                    Thread.sleep(waitTime);
                }
                
                lastRequestTime = System.currentTimeMillis();
            } finally {
                rateLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for rate limit", e);
        }
    }
}

