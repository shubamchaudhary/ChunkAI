package com.examprep.llm.service;

import com.examprep.llm.client.GeminiConfig;
import com.examprep.llm.ratelimit.RateLimitedApiKeyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Batch Embedding Service for High-Throughput Embedding Generation
 *
 * WHY BATCH EMBEDDINGS?
 * ====================
 * Single embedding: 1 API call = 1 embedding
 * Batch embedding:  1 API call = up to 100 embeddings
 *
 * With 3 free tier keys (15 req/min each = 45 req/min total):
 * - Single: 45 embeddings/min max
 * - Batch (20/request): 45 * 20 = 900 embeddings/min!
 *
 * That's a 20x throughput improvement!
 *
 * HOW IT WORKS:
 * =============
 * 1. Collect texts into batches of BATCH_SIZE (default 20)
 * 2. Send single API request with all texts
 * 3. Get back all embeddings in one response
 * 4. Distribute batches across multiple API keys
 *
 * USAGE:
 * ======
 * // For small number of texts
 * List<float[]> embeddings = batchEmbeddingService.generateEmbeddings(texts);
 *
 * // For large documents with parallel processing
 * List<float[]> embeddings = batchEmbeddingService.generateEmbeddingsParallel(texts);
 */
@Service
@Slf4j
public class BatchEmbeddingService {

    private final GeminiConfig config;
    private final WebClient.Builder webClientBuilder;
    private final RateLimitedApiKeyManager apiKeyManager;

    // Batch configuration
    private static final int BATCH_SIZE = 20;              // Texts per API call (Gemini limit is 100)
    private static final int MAX_PARALLEL_BATCHES = 3;     // Process 3 batches in parallel (one per key)
    private static final int MAX_RETRIES = 3;              // Retry failed batches
    private static final long RETRY_DELAY_MS = 2000;       // 2 second between retries

    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_BATCHES);

    public BatchEmbeddingService(GeminiConfig config, WebClient.Builder webClientBuilder,
                                  RateLimitedApiKeyManager apiKeyManager) {
        this.config = config;
        this.webClientBuilder = webClientBuilder;
        this.apiKeyManager = apiKeyManager;
        log.info("BatchEmbeddingService initialized with batch size {} and {} parallel workers",
            BATCH_SIZE, MAX_PARALLEL_BATCHES);
    }

    /**
     * Generate embeddings for multiple texts using batch API
     * Automatically handles batching and retries
     *
     * @param texts List of texts to embed
     * @return List of embeddings in same order as input texts
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Generating embeddings for {} texts using batch API", texts.size());
        long startTime = System.currentTimeMillis();

        // Split into batches
        List<List<String>> batches = splitIntoBatches(texts, BATCH_SIZE);
        log.info("Split into {} batches of up to {} texts each", batches.size(), BATCH_SIZE);

        // Process batches sequentially (use parallel method for concurrent processing)
        List<float[]> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<float[]> batchResult = processBatchWithRetry(batches.get(i), i + 1, batches.size());
            allEmbeddings.addAll(batchResult);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Generated {} embeddings in {}ms ({} ms/embedding)",
            allEmbeddings.size(), duration, allEmbeddings.isEmpty() ? 0 : duration / allEmbeddings.size());

        return allEmbeddings;
    }

    /**
     * Generate embeddings in parallel across multiple API keys
     * Best for large documents with many chunks
     *
     * @param texts List of texts to embed
     * @return List of embeddings in same order as input texts
     */
    public List<float[]> generateEmbeddingsParallel(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Generating embeddings for {} texts using PARALLEL batch API", texts.size());
        long startTime = System.currentTimeMillis();

        // Split into batches
        List<List<String>> batches = splitIntoBatches(texts, BATCH_SIZE);
        int totalBatches = batches.size();

        // Create indexed batch tasks
        List<IndexedBatch> indexedBatches = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            indexedBatches.add(new IndexedBatch(i, batches.get(i)));
        }

        // Process batches in parallel
        List<CompletableFuture<IndexedResult>> futures = indexedBatches.stream()
            .map(ib -> CompletableFuture.supplyAsync(() -> {
                List<float[]> result = processBatchWithRetry(ib.texts, ib.index + 1, totalBatches);
                return new IndexedResult(ib.index, result);
            }, batchExecutor))
            .collect(Collectors.toList());

        // Wait for all and sort by original index
        List<IndexedResult> results = futures.stream()
            .map(CompletableFuture::join)
            .sorted(Comparator.comparingInt(r -> r.index))
            .collect(Collectors.toList());

        // Flatten results maintaining order
        List<float[]> allEmbeddings = results.stream()
            .flatMap(r -> r.embeddings.stream())
            .collect(Collectors.toList());

        long duration = System.currentTimeMillis() - startTime;
        log.info("Generated {} embeddings in {}ms using parallel batches ({} ms/embedding)",
            allEmbeddings.size(), duration, allEmbeddings.isEmpty() ? 0 : duration / allEmbeddings.size());

        return allEmbeddings;
    }

    /**
     * Generate single embedding (falls back to batch of 1)
     */
    public float[] generateEmbedding(String text) {
        List<float[]> results = generateEmbeddings(Collections.singletonList(text));
        if (results.isEmpty()) {
            throw new RuntimeException("Failed to generate embedding");
        }
        return results.get(0);
    }

    /**
     * Process a single batch with retry logic
     */
    private List<float[]> processBatchWithRetry(List<String> texts, int batchNum, int totalBatches) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return processBatch(texts, batchNum, totalBatches);
            } catch (Exception e) {
                lastException = e;
                log.warn("Batch {}/{} failed (attempt {}/{}): {}",
                    batchNum, totalBatches, attempt, MAX_RETRIES, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }

        throw new RuntimeException("Batch " + batchNum + " failed after " + MAX_RETRIES + " attempts",
            lastException);
    }

    /**
     * Process a single batch via Gemini batch embedding API
     */
    private List<float[]> processBatch(List<String> texts, int batchNum, int totalBatches) {
        // Get API key (with rate limiting)
        String apiKey = apiKeyManager.getNextKey();

        String url = String.format(
            "%s/models/%s:batchEmbedContents?key=%s",
            config.getBaseUrl(),
            config.getEmbeddingModel(),
            apiKey
        );

        log.debug("Processing batch {}/{} ({} texts) with API key",
            batchNum, totalBatches, texts.size());

        // Build batch request
        List<Map<String, Object>> requests = texts.stream()
            .map(text -> Map.of(
                "model", "models/" + config.getEmbeddingModel(),
                "content", Map.of(
                    "parts", List.of(Map.of("text", text))
                )
            ))
            .collect(Collectors.toList());

        Map<String, Object> batchRequest = Map.of("requests", requests);

        try {
            BatchEmbeddingResponse response = webClientBuilder.build()
                .post()
                .uri(url)
                .bodyValue(batchRequest)
                .retrieve()
                .bodyToMono(BatchEmbeddingResponse.class)
                .block();

            if (response == null || response.embeddings == null) {
                throw new RuntimeException("Empty response from batch embedding API");
            }

            // Report success to key manager
            apiKeyManager.reportKeySuccess(apiKey);

            // Extract embeddings
            List<float[]> embeddings = response.embeddings.stream()
                .map(e -> e.values)
                .collect(Collectors.toList());

            log.debug("Batch {}/{} completed: {} embeddings generated",
                batchNum, totalBatches, embeddings.size());

            return embeddings;

        } catch (WebClientResponseException e) {
            String errorType = String.valueOf(e.getStatusCode().value());
            String errorMessage = e.getResponseBodyAsString();

            // Report failure to key manager
            apiKeyManager.reportKeyFailure(apiKey, errorType, errorMessage);

            if (e.getStatusCode().value() == 429) {
                throw new RuntimeException("Rate limited (429): " + errorMessage, e);
            }
            if (e.getStatusCode().value() == 403) {
                if (errorMessage.contains("leaked")) {
                    throw new RuntimeException("API key leaked, please use another: " + errorMessage, e);
                }
                throw new RuntimeException("Authentication failed (403): " + errorMessage, e);
            }
            throw new RuntimeException("Batch API error (" + e.getStatusCode() + "): " + errorMessage, e);
        }
    }

    /**
     * Split list into batches of specified size
     */
    private <T> List<List<T>> splitIntoBatches(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        return batches;
    }

    /**
     * Convert float array to pgvector format string
     */
    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    // ====================== Helper Classes ======================

    private static class IndexedBatch {
        final int index;
        final List<String> texts;

        IndexedBatch(int index, List<String> texts) {
            this.index = index;
            this.texts = texts;
        }
    }

    private static class IndexedResult {
        final int index;
        final List<float[]> embeddings;

        IndexedResult(int index, List<float[]> embeddings) {
            this.index = index;
            this.embeddings = embeddings;
        }
    }

    // ====================== Response DTOs ======================

    private static class BatchEmbeddingResponse {
        public List<EmbeddingWrapper> embeddings;
    }

    private static class EmbeddingWrapper {
        public float[] values;
    }
}
