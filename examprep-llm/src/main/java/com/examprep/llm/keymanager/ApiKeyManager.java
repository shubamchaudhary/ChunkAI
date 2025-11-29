package com.examprep.llm.keymanager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages multiple Gemini API keys with round-robin distribution.
 */
@Component
@Slf4j
public class ApiKeyManager {
    
    private final List<String> apiKeys;
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    
    public ApiKeyManager(List<String> apiKeys) {
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one API key is required");
        }
        this.apiKeys = apiKeys;
        log.info("Initialized ApiKeyManager with {} API keys", apiKeys.size());
    }
    
    /**
     * Get the next API key using round-robin.
     */
    public String getNextApiKey() {
        int index = currentIndex.getAndIncrement() % apiKeys.size();
        String key = apiKeys.get(index);
        log.debug("Selected API key at index {} (total keys: {})", index, apiKeys.size());
        return key;
    }
    
    /**
     * Get a specific API key by index (for parallel processing).
     */
    public String getApiKey(int index) {
        if (index < 0 || index >= apiKeys.size()) {
            throw new IllegalArgumentException("Invalid API key index: " + index);
        }
        return apiKeys.get(index);
    }
    
    /**
     * Get the total number of available API keys.
     */
    public int getKeyCount() {
        return apiKeys.size();
    }
    
    /**
     * Get all API keys (for parallel processing).
     */
    public List<String> getAllApiKeys() {
        return List.copyOf(apiKeys);
    }
}

