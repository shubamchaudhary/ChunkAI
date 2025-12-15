package com.examprep.llm.config;

import com.examprep.llm.client.GeminiConfig;
import com.examprep.llm.keymanager.ApiKeyManager;
import com.examprep.llm.ratelimit.RateLimitedApiKeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * Configuration for API Key Management
 *
 * Creates both the legacy ApiKeyManager and the new RateLimitedApiKeyManager.
 * The RateLimitedApiKeyManager is marked as @Primary and should be preferred
 * for new code.
 *
 * Features:
 * - Token bucket rate limiting per key
 * - Automatic key health monitoring
 * - Dynamic key refresh from config
 * - Intelligent key selection
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ApiKeyManagerConfig {

    private final GeminiConfig geminiConfig;

    /**
     * Legacy API key manager for backward compatibility
     */
    @Bean
    public ApiKeyManager apiKeyManager() {
        List<String> apiKeys = geminiConfig.getAllApiKeys();
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("No Gemini API keys configured. Please set gemini.api-key or gemini.api-keys in application.properties");
        }
        return new ApiKeyManager(apiKeys);
    }

    /**
     * New rate-limited API key manager with token bucket algorithm
     * This is the preferred manager for new code
     */
    @Bean
    @Primary
    public RateLimitedApiKeyManager rateLimitedApiKeyManager() {
        List<String> apiKeys = geminiConfig.getAllApiKeys();
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException(
                "No Gemini API keys configured. " +
                "Please set GEMINI_API_KEYS environment variable (comma-separated) " +
                "or gemini.api-keys in application.properties"
            );
        }

        log.info("Initializing RateLimitedApiKeyManager with {} API key(s)", apiKeys.size());
        log.info("Rate limit: 15 requests/minute per key, Total capacity: {} requests/minute",
            apiKeys.size() * 15);

        return new RateLimitedApiKeyManager(apiKeys);
    }

    /**
     * Periodically refresh API keys from config (every 5 minutes)
     * This allows adding new keys without restart
     */
    @Scheduled(fixedDelay = 300000) // 5 minutes
    public void refreshApiKeys() {
        try {
            RateLimitedApiKeyManager manager = rateLimitedApiKeyManager();
            String keysConfig = geminiConfig.getApiKeys();
            if (keysConfig != null && !keysConfig.isEmpty()) {
                manager.updateKeys(keysConfig);
            }
        } catch (Exception e) {
            log.debug("Key refresh skipped: {}", e.getMessage());
        }
    }

    /**
     * Log key health status periodically (every minute)
     */
    @Scheduled(fixedDelay = 60000) // 1 minute
    public void logKeyHealth() {
        try {
            RateLimitedApiKeyManager manager = rateLimitedApiKeyManager();
            log.info("API Key Status: {}", manager.getKeyHealthSummary());
        } catch (Exception e) {
            log.debug("Health check skipped: {}", e.getMessage());
        }
    }
}

