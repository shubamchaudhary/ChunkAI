package com.examprep.llm.keymanager;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class ApiKeyManager {
    
    private final List<ManagedApiKey> apiKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final ScheduledExecutorService healthChecker = Executors.newSingleThreadScheduledExecutor();
    
    // Configuration from properties
    @Value("${gemini.api-keys:}")
    private String apiKeysConfig;
    
    @Value("${gemini.api-key:}")
    private String singleApiKey;
    
    @Value("${gemini.requests-per-minute:15}")
    private int requestsPerMinute;
    
    @Value("${gemini.requests-per-day:1500}")
    private int requestsPerDay;
    
    // Constants
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long COOLDOWN_AFTER_FAILURE_MS = 60_000; // 1 minute
    
    @PostConstruct
    public void initialize() {
        // Load API keys from environment/config
        List<String> keys = loadApiKeysFromConfig();
        
        if (keys.isEmpty()) {
            throw new IllegalStateException("No API keys configured. Set GEMINI_API_KEY or GEMINI_API_KEY_1, GEMINI_API_KEY_2, etc.");
        }
        
        for (int i = 0; i < keys.size(); i++) {
            apiKeys.add(new ManagedApiKey(
                "key_" + (i + 1),
                keys.get(i),
                requestsPerMinute,
                requestsPerDay
            ));
        }
        
        log.info("Initialized {} API keys ({} RPM, {} RPD each)", 
            apiKeys.size(), requestsPerMinute, requestsPerDay);
        
        // Start health checker (runs every 30 seconds)
        healthChecker.scheduleAtFixedRate(
            this::performHealthCheck, 
            30, 30, TimeUnit.SECONDS
        );
    }
    
    /**
     * Get an available API key using smart selection.
     * Blocks if all keys are rate-limited (up to maxWaitMs).
     */
    public ApiKeyLease acquireKey(long maxWaitMs) throws NoAvailableKeyException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        
        while (System.currentTimeMillis() < deadline) {
            // Try to find an available key
            ApiKeyLease lease = tryAcquireKey();
            if (lease != null) {
                return lease;
            }
            
            // Calculate wait time until next key becomes available
            long waitTime = calculateMinWaitTime();
            if (waitTime > 0 && System.currentTimeMillis() + waitTime < deadline) {
                try {
                    log.debug("All keys rate-limited, waiting {}ms", waitTime);
                    Thread.sleep(Math.min(waitTime, 1000)); // Check every second max
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NoAvailableKeyException("Interrupted while waiting for key");
                }
            }
        }
        
        throw new NoAvailableKeyException("No API key available within " + maxWaitMs + "ms");
    }
    
    /**
     * Try to acquire a key without blocking.
     */
    private ApiKeyLease tryAcquireKey() {
        int startIndex = roundRobinCounter.getAndIncrement() % apiKeys.size();
        
        // First pass: try healthy keys with available capacity
        for (int i = 0; i < apiKeys.size(); i++) {
            int index = (startIndex + i) % apiKeys.size();
            ManagedApiKey key = apiKeys.get(index);
            
            if (key.isHealthy() && key.tryAcquire()) {
                log.debug("Acquired key: {}", key.getIdentifier());
                return new ApiKeyLease(key);
            }
        }
        
        // Second pass: try unhealthy keys that might have recovered
        for (int i = 0; i < apiKeys.size(); i++) {
            int index = (startIndex + i) % apiKeys.size();
            ManagedApiKey key = apiKeys.get(index);
            
            if (!key.isHealthy() && key.canRetry() && key.tryAcquire()) {
                log.info("Retrying previously unhealthy key: {}", key.getIdentifier());
                return new ApiKeyLease(key);
            }
        }
        
        return null;
    }
    
    private long calculateMinWaitTime() {
        return apiKeys.stream()
            .filter(ManagedApiKey::isHealthy)
            .mapToLong(ManagedApiKey::getMillisUntilNextSlot)
            .min()
            .orElse(1000);
    }
    
    private void performHealthCheck() {
        for (ManagedApiKey key : apiKeys) {
            key.cleanupOldRequests();
            key.resetDailyCounterIfNeeded();
        }
        
        long healthyKeys = apiKeys.stream().filter(ManagedApiKey::isHealthy).count();
        log.debug("Health check: {}/{} keys healthy", healthyKeys, apiKeys.size());
    }
    
    /**
     * Report success for a key (resets failure counter).
     */
    public void reportSuccess(String keyIdentifier) {
        findKey(keyIdentifier).ifPresent(ManagedApiKey::recordSuccess);
    }
    
    /**
     * Report failure for a key (may disable key temporarily).
     */
    public void reportFailure(String keyIdentifier, Throwable error) {
        findKey(keyIdentifier).ifPresent(key -> key.recordFailure(error));
    }
    
    private Optional<ManagedApiKey> findKey(String identifier) {
        return apiKeys.stream()
            .filter(k -> k.getIdentifier().equals(identifier))
            .findFirst();
    }
    
    private List<String> loadApiKeysFromConfig() {
        List<String> keys = new ArrayList<>();
        
        // Try loading multiple keys (GEMINI_API_KEY_1, GEMINI_API_KEY_2, etc.)
        for (int i = 1; i <= 10; i++) {
            String key = System.getenv("GEMINI_API_KEY_" + i);
            if (key == null || key.isBlank()) {
                // Also check properties
                key = System.getProperty("gemini.api.key." + i);
            }
            if (key != null && !key.isBlank()) {
                keys.add(key);
            }
        }
        
        // Fallback to single key (check in order: env var, system property, Spring property)
        if (keys.isEmpty()) {
            String singleKey = System.getenv("GEMINI_API_KEY");
            if (singleKey == null || singleKey.isBlank()) {
                singleKey = System.getProperty("gemini.api.key");
            }
            if ((singleKey == null || singleKey.isBlank()) && singleApiKey != null && !singleApiKey.isBlank()) {
                singleKey = singleApiKey;
            }
            if (singleKey != null && !singleKey.isBlank()) {
                keys.add(singleKey);
            }
        }
        
        // Also check comma-separated config string
        if (keys.isEmpty() && apiKeysConfig != null && !apiKeysConfig.isBlank()) {
            String[] configKeys = apiKeysConfig.split(",");
            for (String key : configKeys) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) {
                    keys.add(trimmed);
                }
            }
        }
        
        return keys;
    }
    
    // ========== Inner Classes ==========
    
    /**
     * Represents a managed API key with rate limiting.
     */
    @Getter
    public static class ManagedApiKey {
        private final String identifier;
        private final String apiKey;
        private final int maxRequestsPerMinute;
        private final int maxRequestsPerDay;
        
        // Rate limiting using Token Bucket
        private final TokenBucket minuteBucket;
        private final AtomicInteger dailyRequestCount = new AtomicInteger(0);
        private final AtomicLong lastDayReset = new AtomicLong(System.currentTimeMillis());
        
        // Health tracking
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private final AtomicLong lastSuccessTime = new AtomicLong(System.currentTimeMillis());
        
        public ManagedApiKey(String identifier, String apiKey, int rpm, int rpd) {
            this.identifier = identifier;
            this.apiKey = apiKey;
            this.maxRequestsPerMinute = rpm;
            this.maxRequestsPerDay = rpd;
            this.minuteBucket = new TokenBucket(rpm, rpm, 60_000); // Refill every minute
        }
        
        public boolean tryAcquire() {
            if (dailyRequestCount.get() >= maxRequestsPerDay) {
                return false;
            }
            
            if (minuteBucket.tryConsume(1)) {
                dailyRequestCount.incrementAndGet();
                return true;
            }
            
            return false;
        }
        
        public boolean isHealthy() {
            return consecutiveFailures.get() < MAX_CONSECUTIVE_FAILURES;
        }
        
        public boolean canRetry() {
            long timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get();
            return timeSinceFailure > COOLDOWN_AFTER_FAILURE_MS;
        }
        
        public void recordSuccess() {
            consecutiveFailures.set(0);
            lastSuccessTime.set(System.currentTimeMillis());
        }
        
        public void recordFailure(Throwable error) {
            int failures = consecutiveFailures.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            log.warn("API key {} failed ({} consecutive): {}", 
                identifier, failures, error.getMessage());
        }
        
        public long getMillisUntilNextSlot() {
            return minuteBucket.getMillisUntilNextToken();
        }
        
        public void cleanupOldRequests() {
            minuteBucket.refill();
        }
        
        public void resetDailyCounterIfNeeded() {
            long now = System.currentTimeMillis();
            long lastReset = lastDayReset.get();
            
            // Reset if more than 24 hours since last reset
            if (now - lastReset > 24 * 60 * 60 * 1000) {
                if (lastDayReset.compareAndSet(lastReset, now)) {
                    dailyRequestCount.set(0);
                    log.info("Daily counter reset for key {}", identifier);
                }
            }
        }
    }
    
    /**
     * Lease object that auto-releases on close.
     */
    @Getter
    public static class ApiKeyLease implements AutoCloseable {
        private final ManagedApiKey managedKey;
        private final long acquiredAt;
        private boolean released = false;
        
        public ApiKeyLease(ManagedApiKey key) {
            this.managedKey = key;
            this.acquiredAt = System.currentTimeMillis();
        }
        
        public String getApiKey() {
            return managedKey.getApiKey();
        }
        
        public String getIdentifier() {
            return managedKey.getIdentifier();
        }
        
        @Override
        public void close() {
            // Nothing to release, but useful for try-with-resources pattern
            released = true;
        }
    }
    
    public static class NoAvailableKeyException extends RuntimeException {
        public NoAvailableKeyException(String message) {
            super(message);
        }
    }
}

