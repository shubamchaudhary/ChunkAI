package com.examprep.llm.service;

import com.examprep.llm.client.GeminiClient;
import com.examprep.llm.keymanager.ApiKeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {
    
    private final GeminiClient geminiClient;
    private final ApiKeyManager apiKeyManager;
    
    // Rate limiting: add delay between embedding requests to avoid API limits
    private static final long EMBEDDING_DELAY_MS = 500; // 500ms delay between requests (5 requests per second max)
    private static long lastEmbeddingTime = 0;
    private static final Object embeddingLock = new Object();
    
    /**
     * Generate embedding for a single text with automatic retry on leaked keys
     */
    public float[] generateEmbedding(String text) {
        log.debug("Generating embedding for text of length: {}", text.length());
        
        // Rate limiting: ensure minimum delay between requests
        synchronized (embeddingLock) {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastRequest = currentTime - lastEmbeddingTime;
            if (timeSinceLastRequest < EMBEDDING_DELAY_MS) {
                try {
                    Thread.sleep(EMBEDDING_DELAY_MS - timeSinceLastRequest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastEmbeddingTime = System.currentTimeMillis();
        }
        
        // Try with round-robin key first
        String apiKey = apiKeyManager.getNextApiKey();
        int maxRetries = apiKeyManager.getKeyCount();
        int attempts = 0;
        
        while (attempts < maxRetries) {
            try {
                return geminiClient.generateEmbedding(text, apiKey);
            } catch (RuntimeException e) {
                // Check if it's a leaked key error
                if (e.getMessage() != null && e.getMessage().contains("leaked")) {
                    log.warn("API key reported as leaked, trying next key (attempt {}/{})", 
                        attempts + 1, maxRetries);
                    attempts++;
                    if (attempts < maxRetries) {
                        apiKey = apiKeyManager.getNextApiKey(); // Try next key
                        continue;
                    }
                }
                // If not leaked error or all keys exhausted, throw
                throw e;
            }
        }
        
        throw new RuntimeException("All API keys failed. Please check GEMINI_API_KEYS environment variable.");
    }
    
    /**
     * Generate embeddings for multiple texts (batch processing)
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        log.info("Generating embeddings for {} texts", texts.size());
        
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(generateEmbedding(text));
            
            // Rate limiting - simple delay between requests
            try {
                Thread.sleep(100); // 100ms between requests
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

