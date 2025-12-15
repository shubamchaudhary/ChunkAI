package com.examprep.llm.ratelimit;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Rate-Limited API Key Manager with Intelligent Key Selection
 *
 * FEATURES:
 * =========
 * 1. TOKEN BUCKET RATE LIMITING: Each key has its own token bucket
 * 2. DYNAMIC KEY ADDITION: Auto-detects new keys from comma-separated config
 * 3. KEY FAILURE HANDLING: Tracks failed keys and avoids them
 * 4. INTELLIGENT SELECTION: Picks healthiest key with most tokens
 * 5. AUTO-RECOVERY: Failed keys automatically recover after cooldown
 * 6. LOAD DISTRIBUTION: Evenly distributes load across healthy keys
 *
 * CONFIGURATION:
 * ==============
 * Set GEMINI_API_KEYS environment variable or gemini.api-keys property:
 *   GEMINI_API_KEYS=key1,key2,key3
 *
 * When you add more keys, just update the config - they auto-register!
 *
 * USAGE:
 * ======
 * 1. getNextKey() - Gets best available key (blocks if all rate-limited)
 * 2. reportKeyFailure(key, error) - Reports a key failure
 * 3. reportKeySuccess(key) - Reports successful use (for recovery tracking)
 * 4. getKeyHealth() - Returns health status of all keys (for monitoring)
 */
@Slf4j
public class RateLimitedApiKeyManager {

    // Configuration for Gemini Free Tier
    private static final double REQUESTS_PER_MINUTE = 15.0;  // Free tier limit
    private static final double BURST_CAPACITY = 15.0;       // Allow burst up to limit
    private static final long MAX_WAIT_MS = 30000;           // 30 second max wait
    private static final long FAILURE_COOLDOWN_MS = 60000;   // 1 minute cooldown after failure
    private static final int MAX_CONSECUTIVE_FAILURES = 3;   // Disable key after 3 failures
    private static final long KEY_DISABLE_DURATION_MS = 300000; // 5 minute disable after max failures

    private final Map<String, TokenBucket> keyBuckets = new ConcurrentHashMap<>();
    private final Map<String, KeyHealth> keyHealthMap = new ConcurrentHashMap<>();
    private final List<String> apiKeys = new ArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Initialize with list of API keys
     */
    public RateLimitedApiKeyManager(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("At least one API key is required");
        }

        for (String key : keys) {
            if (key != null && !key.trim().isEmpty()) {
                addKey(key.trim());
            }
        }

        log.info("RateLimitedApiKeyManager initialized with {} keys ({}req/min per key, {}req/min total)",
            apiKeys.size(), REQUESTS_PER_MINUTE, REQUESTS_PER_MINUTE * apiKeys.size());
    }

    /**
     * Add a new API key dynamically
     */
    public void addKey(String apiKey) {
        lock.writeLock().lock();
        try {
            if (!apiKeys.contains(apiKey)) {
                String keyId = "key-" + (apiKeys.size() + 1);
                apiKeys.add(apiKey);
                keyBuckets.put(apiKey, new TokenBucket(keyId, REQUESTS_PER_MINUTE, BURST_CAPACITY));
                keyHealthMap.put(apiKey, new KeyHealth(keyId));
                log.info("Added new API key: {} (total keys: {})", keyId, apiKeys.size());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Update keys from comma-separated string (for dynamic config refresh)
     */
    public void updateKeys(String commaSeparatedKeys) {
        if (commaSeparatedKeys == null || commaSeparatedKeys.trim().isEmpty()) {
            return;
        }

        String[] newKeys = commaSeparatedKeys.split(",");
        for (String key : newKeys) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                addKey(trimmed);
            }
        }
    }

    /**
     * Get the next available API key with intelligent selection
     * Prefers keys with more available tokens
     * Blocks if all keys are rate-limited (up to MAX_WAIT_MS)
     *
     * @return API key ready for use
     * @throws RuntimeException if no key available within timeout
     */
    public String getNextKey() {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            String key = tryGetBestKey();
            if (key != null) {
                return key;
            }

            // All keys exhausted, wait for refill
            long minWait = getMinWaitTime();
            if (minWait > 0) {
                try {
                    log.debug("All keys exhausted, waiting {}ms for refill", minWait);
                    Thread.sleep(Math.min(minWait, 1000)); // Check every second max
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for API key", e);
                }
            }
        }

        throw new RuntimeException("No API keys available after " + MAX_WAIT_MS + "ms. " +
            "All keys may be rate-limited or disabled. Status: " + getKeyHealthSummary());
    }

    /**
     * Get a specific key by index (for document-to-key binding)
     * Uses hash-based distribution to ensure same document always uses same key
     */
    public String getKeyForDocument(UUID documentId) {
        lock.readLock().lock();
        try {
            if (apiKeys.isEmpty()) {
                throw new RuntimeException("No API keys available");
            }

            // Hash document ID to get consistent key assignment
            int index = Math.abs(documentId.hashCode()) % apiKeys.size();
            String key = apiKeys.get(index);

            // If assigned key is disabled, find next healthy key
            KeyHealth health = keyHealthMap.get(key);
            if (health != null && health.isDisabled()) {
                key = findHealthyKey();
            }

            // Acquire token from bucket
            TokenBucket bucket = keyBuckets.get(key);
            if (bucket != null && bucket.acquire(MAX_WAIT_MS)) {
                return key;
            }

            // Fallback to any available key
            return getNextKey();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Try to get the best available key (non-blocking)
     */
    private String tryGetBestKey() {
        lock.readLock().lock();
        try {
            // Sort keys by health (most tokens first, excluding disabled keys)
            List<String> sortedKeys = apiKeys.stream()
                .filter(k -> {
                    KeyHealth health = keyHealthMap.get(k);
                    return health == null || !health.isDisabled();
                })
                .sorted((a, b) -> {
                    TokenBucket bucketA = keyBuckets.get(a);
                    TokenBucket bucketB = keyBuckets.get(b);
                    return Double.compare(
                        bucketB != null ? bucketB.getAvailableTokens() : 0,
                        bucketA != null ? bucketA.getAvailableTokens() : 0
                    );
                })
                .collect(Collectors.toList());

            // Try to acquire from healthiest key first
            for (String key : sortedKeys) {
                TokenBucket bucket = keyBuckets.get(key);
                if (bucket != null && bucket.tryAcquire()) {
                    log.debug("Selected {} with {:.1f} tokens available",
                        bucket.getKeyId(), bucket.getAvailableTokens());
                    return key;
                }
            }

            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find any healthy key (for fallback)
     */
    private String findHealthyKey() {
        for (String key : apiKeys) {
            KeyHealth health = keyHealthMap.get(key);
            if (health == null || !health.isDisabled()) {
                return key;
            }
        }
        // All keys disabled, return first one (will be rate-limited but at least tries)
        return apiKeys.isEmpty() ? null : apiKeys.get(0);
    }

    /**
     * Get minimum wait time across all keys
     */
    private long getMinWaitTime() {
        long minWait = Long.MAX_VALUE;
        for (TokenBucket bucket : keyBuckets.values()) {
            long wait = bucket.getWaitTimeMs();
            if (wait < minWait) {
                minWait = wait;
            }
        }
        return minWait == Long.MAX_VALUE ? 100 : minWait;
    }

    /**
     * Report a key failure (e.g., 429 rate limit, 403 forbidden)
     * Key will be temporarily avoided based on failure type
     */
    public void reportKeyFailure(String apiKey, String errorType, String errorMessage) {
        KeyHealth health = keyHealthMap.get(apiKey);
        if (health == null) {
            return;
        }

        health.recordFailure(errorType, errorMessage);

        // If rate limited, deplete the bucket
        if (errorType.contains("429") || errorType.toLowerCase().contains("rate")) {
            TokenBucket bucket = keyBuckets.get(apiKey);
            if (bucket != null) {
                bucket.markDepleted();
            }
        }

        log.warn("API key {} failure recorded: {} - {} (consecutive failures: {})",
            health.keyId, errorType, errorMessage, health.consecutiveFailures);

        // Disable key if too many consecutive failures
        if (health.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            health.disable(KEY_DISABLE_DURATION_MS);
            log.error("API key {} DISABLED for {}ms due to {} consecutive failures",
                health.keyId, KEY_DISABLE_DURATION_MS, health.consecutiveFailures);
        }
    }

    /**
     * Report successful key usage (resets failure count)
     */
    public void reportKeySuccess(String apiKey) {
        KeyHealth health = keyHealthMap.get(apiKey);
        if (health != null) {
            health.recordSuccess();
        }
    }

    /**
     * Get health summary for all keys (for monitoring/dashboards)
     */
    public List<KeyHealthStatus> getKeyHealth() {
        List<KeyHealthStatus> statuses = new ArrayList<>();

        lock.readLock().lock();
        try {
            for (String key : apiKeys) {
                TokenBucket bucket = keyBuckets.get(key);
                KeyHealth health = keyHealthMap.get(key);

                if (bucket != null && health != null) {
                    statuses.add(new KeyHealthStatus(
                        health.keyId,
                        bucket.getAvailableTokens(),
                        bucket.getCapacity(),
                        bucket.getFillPercentage(),
                        health.isDisabled(),
                        health.consecutiveFailures,
                        health.totalRequests,
                        health.totalFailures,
                        health.lastFailureTime,
                        health.lastFailureMessage
                    ));
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        return statuses;
    }

    /**
     * Get summary string for logging
     */
    public String getKeyHealthSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Keys: ");
        for (KeyHealthStatus status : getKeyHealth()) {
            sb.append(String.format("[%s: %.0f%% %s] ",
                status.keyId,
                status.fillPercentage,
                status.isDisabled ? "DISABLED" : "OK"));
        }
        return sb.toString();
    }

    /**
     * Get total number of keys
     */
    public int getKeyCount() {
        lock.readLock().lock();
        try {
            return apiKeys.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get number of healthy (non-disabled) keys
     */
    public int getHealthyKeyCount() {
        lock.readLock().lock();
        try {
            return (int) apiKeys.stream()
                .filter(k -> {
                    KeyHealth health = keyHealthMap.get(k);
                    return health == null || !health.isDisabled();
                })
                .count();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ====================== Inner Classes ======================

    /**
     * Tracks health metrics for a single API key
     */
    private static class KeyHealth {
        final String keyId;
        volatile int consecutiveFailures = 0;
        volatile long totalRequests = 0;
        volatile long totalFailures = 0;
        volatile Instant lastFailureTime;
        volatile String lastFailureMessage;
        volatile Instant disabledUntil;

        KeyHealth(String keyId) {
            this.keyId = keyId;
        }

        void recordFailure(String errorType, String message) {
            consecutiveFailures++;
            totalFailures++;
            lastFailureTime = Instant.now();
            lastFailureMessage = errorType + ": " + message;
        }

        void recordSuccess() {
            totalRequests++;
            consecutiveFailures = 0;
        }

        void disable(long durationMs) {
            disabledUntil = Instant.now().plusMillis(durationMs);
        }

        boolean isDisabled() {
            if (disabledUntil == null) {
                return false;
            }
            if (Instant.now().isAfter(disabledUntil)) {
                disabledUntil = null; // Auto-recover
                consecutiveFailures = 0;
                return false;
            }
            return true;
        }
    }

    /**
     * DTO for key health status (for monitoring/API)
     */
    public static class KeyHealthStatus {
        public final String keyId;
        public final double availableTokens;
        public final double capacity;
        public final double fillPercentage;
        public final boolean isDisabled;
        public final int consecutiveFailures;
        public final long totalRequests;
        public final long totalFailures;
        public final Instant lastFailureTime;
        public final String lastFailureMessage;

        public KeyHealthStatus(String keyId, double availableTokens, double capacity,
                              double fillPercentage, boolean isDisabled, int consecutiveFailures,
                              long totalRequests, long totalFailures, Instant lastFailureTime,
                              String lastFailureMessage) {
            this.keyId = keyId;
            this.availableTokens = availableTokens;
            this.capacity = capacity;
            this.fillPercentage = fillPercentage;
            this.isDisabled = isDisabled;
            this.consecutiveFailures = consecutiveFailures;
            this.totalRequests = totalRequests;
            this.totalFailures = totalFailures;
            this.lastFailureTime = lastFailureTime;
            this.lastFailureMessage = lastFailureMessage;
        }
    }
}
