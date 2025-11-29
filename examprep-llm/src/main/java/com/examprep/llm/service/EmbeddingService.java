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
    
    // Per-key rate limiting: track last request time for each API key separately
    private static final long EMBEDDING_DELAY_MS = 300; // 300ms delay between requests per key
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> lastRequestTimeByKeyIndex = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Generate embedding for a single text with automatic retry on leaked keys
     * Uses round-robin key selection with per-key rate limiting
     */
    public float[] generateEmbedding(String text) {
        log.debug("Generating embedding for text of length: {}", text.length());
        
        // Try with round-robin key first
        String apiKey = apiKeyManager.getNextApiKey();
        int keyIndex = findKeyIndex(apiKey);
        int maxRetries = apiKeyManager.getKeyCount();
        int attempts = 0;
        
        while (attempts < maxRetries) {
            try {
                // Per-key rate limiting: only delay if same key was used recently
                enforceRateLimit(keyIndex);
                return geminiClient.generateEmbedding(text, apiKey);
            } catch (RuntimeException e) {
                // Check if it's a leaked key error
                if (e.getMessage() != null && e.getMessage().contains("leaked")) {
                    log.warn("API key {} reported as leaked, trying next key (attempt {}/{})", 
                        keyIndex, attempts + 1, maxRetries);
                    attempts++;
                    if (attempts < maxRetries) {
                        apiKey = apiKeyManager.getNextApiKey(); // Try next key
                        keyIndex = findKeyIndex(apiKey);
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
     * Generate embedding using a specific API key (for parallel processing across keys)
     */
    public float[] generateEmbedding(String text, int keyIndex) {
        log.debug("Generating embedding for text of length: {} using API key {}", text.length(), keyIndex);
        
        String apiKey = apiKeyManager.getApiKey(keyIndex);
        
        // Per-key rate limiting
        enforceRateLimit(keyIndex);
        
        return geminiClient.generateEmbedding(text, apiKey);
    }
    
    private void enforceRateLimit(int keyIndex) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastRequestTimeByKeyIndex.get(keyIndex);
        
        if (lastTime != null) {
            long timeSinceLastRequest = currentTime - lastTime;
            if (timeSinceLastRequest < EMBEDDING_DELAY_MS) {
                try {
                    Thread.sleep(EMBEDDING_DELAY_MS - timeSinceLastRequest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        lastRequestTimeByKeyIndex.put(keyIndex, System.currentTimeMillis());
    }
    
    private int findKeyIndex(String apiKey) {
        List<String> allKeys = apiKeyManager.getAllApiKeys();
        for (int i = 0; i < allKeys.size(); i++) {
            if (allKeys.get(i).equals(apiKey)) {
                return i;
            }
        }
        return 0; // Default to first key if not found
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

