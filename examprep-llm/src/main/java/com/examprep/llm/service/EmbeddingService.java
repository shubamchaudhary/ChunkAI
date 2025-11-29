package com.examprep.llm.service;

import com.examprep.llm.client.GeminiClient;
import com.examprep.llm.embedding.GeminiBatchEmbeddingService;
import com.examprep.llm.keymanager.ApiKeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {
    
    private final GeminiClient geminiClient;
    private final ApiKeyManager apiKeyManager;
    private final GeminiBatchEmbeddingService batchEmbeddingService;
    
    @Value("${embedding.use-batch-api:true}")
    private boolean useBatchApi;
    
    /**
     * Generate embedding for a single text.
     * Uses batch API if enabled (more efficient), otherwise falls back to single API call.
     */
    public float[] generateEmbedding(String text) {
        log.debug("Generating embedding for text of length: {} | useBatchApi={}", text.length(), useBatchApi);
        
        if (useBatchApi) {
            // Use batch API with single item (more efficient for rate limiting)
            List<float[]> embeddings = batchEmbeddingService.generateBatchEmbeddings(List.of(text));
            if (embeddings.isEmpty()) {
                throw new RuntimeException("Batch embedding API returned empty result");
            }
            return embeddings.get(0);
        } else {
            // Fallback to single API call (legacy method)
            String leaseIdentifier = null;
            try (var lease = apiKeyManager.acquireKey(300_000)) { // 5 minute timeout for embeddings
                leaseIdentifier = lease.getIdentifier();
                float[] embedding = geminiClient.generateEmbedding(text, lease.getApiKey());
                apiKeyManager.reportSuccess(leaseIdentifier);
                return embedding;
            } catch (ApiKeyManager.NoAvailableKeyException e) {
                log.error("No API key available for embedding generation", e);
                throw new RuntimeException("No API key available: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("Failed to generate embedding", e);
                if (leaseIdentifier != null) {
                    apiKeyManager.reportFailure(leaseIdentifier, e);
                }
                throw new RuntimeException("Failed to generate embedding: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Generate embeddings for multiple texts (batch processing with rate limiting)
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        log.info("Generating embeddings for {} texts", texts.size());
        
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(generateEmbedding(text));
            // ApiKeyManager will automatically rate-limit, no need for manual sleep
        }
        
        return embeddings;
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
}

