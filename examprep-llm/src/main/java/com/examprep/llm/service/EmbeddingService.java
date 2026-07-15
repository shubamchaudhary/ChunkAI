package com.examprep.llm.service;

import com.examprep.llm.client.GeminiClient;
import com.examprep.llm.keymanager.ApiKeyManager;
import com.examprep.llm.ratelimit.RateLimitedApiKeyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Enhanced Embedding Service with Batch Support and Intelligent Rate Limiting
 *
 * This service provides two modes of operation:
 * 1. SINGLE EMBEDDING: For individual text chunks (uses token bucket rate limiting)
 * 2. BATCH EMBEDDING: For multiple texts (20x more efficient via batch API)
 *
 * RECOMMENDED USAGE:
 * ==================
 * - For documents: Use batch methods (generateEmbeddingsParallel)
 * - For queries: Use single method (generateEmbedding)
 *
 * The service automatically:
 * - Distributes load across all configured API keys
 * - Handles rate limiting with token bucket algorithm
 * - Retries on failures with exponential backoff
 * - Disables failing keys temporarily
 */
@Service
@Slf4j
public class EmbeddingService {

    private final GeminiClient geminiClient;
    private final ApiKeyManager legacyApiKeyManager;
    private final RateLimitedApiKeyManager rateLimitedApiKeyManager;
    private final BatchEmbeddingService batchEmbeddingService;

    @Autowired
    public EmbeddingService(
            GeminiClient geminiClient,
            ApiKeyManager apiKeyManager,
            RateLimitedApiKeyManager rateLimitedApiKeyManager,
            @Autowired(required = false) BatchEmbeddingService batchEmbeddingService) {
        this.geminiClient = geminiClient;
        this.legacyApiKeyManager = apiKeyManager;
        this.rateLimitedApiKeyManager = rateLimitedApiKeyManager;
        this.batchEmbeddingService = batchEmbeddingService;

        log.info("EmbeddingService initialized with {} API keys, batch support: {}",
            rateLimitedApiKeyManager.getKeyCount(),
            batchEmbeddingService != null ? "ENABLED" : "DISABLED");
    }

    /**
     * Generate embedding for a single text using rate-limited key selection
     * Best for: Query embeddings, single text processing
     */
    public float[] generateEmbedding(String text) {
        log.debug("Generating embedding for text of length: {}", text.length());

        int maxRetries = rateLimitedApiKeyManager.getKeyCount();
        Exception lastException = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String apiKey = rateLimitedApiKeyManager.getNextKey();

            try {
                float[] embedding = geminiClient.generateEmbedding(text, apiKey);
                rateLimitedApiKeyManager.reportKeySuccess(apiKey);
                return embedding;

            } catch (RuntimeException e) {
                lastException = e;
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";

                // Categorize error
                String errorType = categorizeError(errorMessage);
                rateLimitedApiKeyManager.reportKeyFailure(apiKey, errorType, errorMessage);

                log.warn("Embedding failed with key (attempt {}/{}): {} - {}",
                    attempt + 1, maxRetries, errorType, errorMessage);

                // If it's a leaked key, try next immediately
                if (errorMessage.contains("leaked")) {
                    continue;
                }

                // For rate limits, the key manager will handle waiting
                if (errorType.contains("429")) {
                    continue;
                }

                // For other errors, don't retry with same key
                if (attempt < maxRetries - 1) {
                    continue;
                }
            }
        }

        throw new RuntimeException("All API keys failed after " + maxRetries + " attempts. " +
            "Status: " + rateLimitedApiKeyManager.getKeyHealthSummary(),
            lastException);
    }

    /**
     * Generate embedding using document-bound key (for consistent key assignment)
     * Best for: Document processing where all chunks should use same key
     */
    public float[] generateEmbedding(String text, UUID documentId) {
        log.debug("Generating embedding for document {} text of length: {}", documentId, text.length());

        String apiKey = rateLimitedApiKeyManager.getKeyForDocument(documentId);

        try {
            float[] embedding = geminiClient.generateEmbedding(text, apiKey);
            rateLimitedApiKeyManager.reportKeySuccess(apiKey);
            return embedding;

        } catch (RuntimeException e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
            String errorType = categorizeError(errorMessage);
            rateLimitedApiKeyManager.reportKeyFailure(apiKey, errorType, errorMessage);

            // Fall back to any available key
            log.warn("Document-bound key failed, falling back to general pool");
            return generateEmbedding(text);
        }
    }

    /**
     * Generate embedding using a specific key index (legacy compatibility)
     */
    public float[] generateEmbedding(String text, int keyIndex) {
        log.debug("Generating embedding for text of length: {} using key index {}", text.length(), keyIndex);

        String apiKey = legacyApiKeyManager.getApiKey(keyIndex);
        return geminiClient.generateEmbedding(text, apiKey);
    }

    /**
     * Generate embeddings for multiple texts using BATCH API (20x faster!)
     * Best for: Document processing with many chunks
     *
     * @param texts List of texts to embed
     * @return List of embeddings in same order
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (batchEmbeddingService != null) {
            log.info("Using batch embedding for {} texts", texts.size());
            return batchEmbeddingService.generateEmbeddings(texts);
        }

        // Fallback to sequential processing
        log.warn("Batch embedding not available, falling back to sequential processing");
        return texts.stream()
            .map(this::generateEmbedding)
            .toList();
    }

    /**
     * Generate embeddings in parallel using batch API across multiple keys
     * Best for: Large documents, high-throughput scenarios
     *
     * @param texts List of texts to embed
     * @return List of embeddings in same order
     */
    public List<float[]> generateEmbeddingsParallel(List<String> texts) {
        if (batchEmbeddingService != null) {
            log.info("Using PARALLEL batch embedding for {} texts", texts.size());
            return batchEmbeddingService.generateEmbeddingsParallel(texts);
        }

        // Fallback to regular batch
        return generateEmbeddings(texts);
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

    /**
     * Get current API key health status (for monitoring)
     */
    public List<RateLimitedApiKeyManager.KeyHealthStatus> getKeyHealth() {
        return rateLimitedApiKeyManager.getKeyHealth();
    }

    /**
     * Categorize error for key manager reporting
     */
    private String categorizeError(String errorMessage) {
        if (errorMessage == null) return "UNKNOWN";
        String lower = errorMessage.toLowerCase();

        if (lower.contains("429") || lower.contains("rate")) return "429_RATE_LIMIT";
        if (lower.contains("403") || lower.contains("forbidden")) return "403_FORBIDDEN";
        if (lower.contains("401") || lower.contains("unauthorized")) return "401_UNAUTHORIZED";
        if (lower.contains("leaked")) return "KEY_LEAKED";
        if (lower.contains("timeout")) return "TIMEOUT";
        if (lower.contains("connection")) return "CONNECTION_ERROR";

        return "UNKNOWN";
    }
}

