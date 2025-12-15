package com.examprep.llm.ratelimit;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token Bucket Rate Limiter Implementation
 *
 * HOW IT WORKS:
 * ============
 * The Token Bucket algorithm is like a bucket that fills with tokens at a constant rate.
 * Each API request consumes one token. If the bucket is empty, requests must wait.
 *
 * Key Concepts:
 * 1. CAPACITY: Maximum tokens the bucket can hold (burst capacity)
 * 2. REFILL RATE: How fast tokens are added back (tokens per second)
 * 3. AVAILABLE TOKENS: Current tokens in the bucket
 *
 * Example with Gemini Free Tier (15 req/min = 0.25 req/sec):
 * - Capacity: 15 tokens (allows burst of 15 requests)
 * - Refill Rate: 0.25 tokens/sec (15 per minute)
 * - If bucket has 15 tokens, you can make 15 requests immediately
 * - After that, tokens refill at 1 token every 4 seconds
 *
 * ADVANTAGES over simple delay:
 * - Allows bursting when capacity is available
 * - Smoothly handles traffic spikes
 * - Automatically recovers during idle periods
 * - Fair distribution across multiple keys
 */
@Slf4j
public class TokenBucket {

    private final String keyId;
    private final double capacity;           // Max tokens (burst limit)
    private final double refillRatePerSecond; // Tokens added per second
    private final AtomicLong availableTokens; // Current available tokens (scaled by 1000 for precision)
    private volatile long lastRefillTimestamp;
    private final ReentrantLock lock = new ReentrantLock();

    // Scaling factor for precision (store tokens * 1000)
    private static final long PRECISION = 1000L;

    /**
     * Create a token bucket for rate limiting
     *
     * @param keyId Identifier for this bucket (for logging)
     * @param requestsPerMinute Maximum requests allowed per minute
     * @param burstCapacity Maximum burst size (usually same as requestsPerMinute)
     */
    public TokenBucket(String keyId, double requestsPerMinute, double burstCapacity) {
        this.keyId = keyId;
        this.capacity = burstCapacity;
        this.refillRatePerSecond = requestsPerMinute / 60.0;
        this.availableTokens = new AtomicLong((long)(burstCapacity * PRECISION));
        this.lastRefillTimestamp = System.nanoTime();

        log.info("TokenBucket[{}] created: capacity={}, refillRate={}/sec",
            keyId, burstCapacity, String.format("%.3f", refillRatePerSecond));
    }

    /**
     * Try to acquire a token without blocking
     *
     * @return true if token was acquired, false if bucket is empty
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Try to acquire multiple tokens without blocking
     *
     * @param tokens Number of tokens to acquire
     * @return true if tokens were acquired, false if not enough tokens
     */
    public boolean tryAcquire(int tokens) {
        lock.lock();
        try {
            refill();

            long required = tokens * PRECISION;
            long current = availableTokens.get();

            if (current >= required) {
                availableTokens.addAndGet(-required);
                log.debug("TokenBucket[{}]: Acquired {} token(s), remaining: {}",
                    keyId, tokens, availableTokens.get() / (double) PRECISION);
                return true;
            }

            log.debug("TokenBucket[{}]: Cannot acquire {} token(s), available: {}",
                keyId, tokens, current / (double) PRECISION);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquire a token, blocking if necessary until one is available
     *
     * @param maxWaitMs Maximum time to wait in milliseconds
     * @return true if token was acquired, false if timeout
     */
    public boolean acquire(long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;

        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire()) {
                return true;
            }

            // Calculate wait time until next token
            long waitMs = getWaitTimeMs();
            if (waitMs > 0) {
                try {
                    Thread.sleep(Math.min(waitMs, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Get estimated wait time until a token is available
     *
     * @return milliseconds to wait, 0 if token available now
     */
    public long getWaitTimeMs() {
        lock.lock();
        try {
            refill();

            long current = availableTokens.get();
            if (current >= PRECISION) {
                return 0;
            }

            // Calculate time to get 1 token
            double tokensNeeded = 1.0 - (current / (double) PRECISION);
            double secondsToWait = tokensNeeded / refillRatePerSecond;
            return (long)(secondsToWait * 1000) + 1; // +1 to avoid rounding issues
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get current available tokens (for monitoring/selection)
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens.get() / (double) PRECISION;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get bucket capacity
     */
    public double getCapacity() {
        return capacity;
    }

    /**
     * Get key identifier
     */
    public String getKeyId() {
        return keyId;
    }

    /**
     * Check if bucket is healthy (has tokens available)
     */
    public boolean isHealthy() {
        return getAvailableTokens() >= 1.0;
    }

    /**
     * Get fill percentage (for monitoring)
     */
    public double getFillPercentage() {
        return (getAvailableTokens() / capacity) * 100.0;
    }

    /**
     * Refill tokens based on elapsed time
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillTimestamp;

        if (elapsed <= 0) {
            return;
        }

        // Calculate tokens to add
        double secondsElapsed = elapsed / 1_000_000_000.0;
        double tokensToAdd = secondsElapsed * refillRatePerSecond;

        if (tokensToAdd > 0) {
            long current = availableTokens.get();
            long newTokens = Math.min(
                (long)(capacity * PRECISION),
                current + (long)(tokensToAdd * PRECISION)
            );
            availableTokens.set(newTokens);
            lastRefillTimestamp = now;
        }
    }

    /**
     * Mark this bucket as temporarily depleted (e.g., after 429 error)
     * Removes all tokens to prevent further requests
     */
    public void markDepleted() {
        lock.lock();
        try {
            availableTokens.set(0);
            log.warn("TokenBucket[{}]: Marked as depleted (0 tokens)", keyId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reset bucket to full capacity (e.g., after quota reset)
     */
    public void reset() {
        lock.lock();
        try {
            availableTokens.set((long)(capacity * PRECISION));
            lastRefillTimestamp = System.nanoTime();
            log.info("TokenBucket[{}]: Reset to full capacity ({})", keyId, capacity);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return String.format("TokenBucket[%s: %.1f/%.0f tokens (%.0f%%)]",
            keyId, getAvailableTokens(), capacity, getFillPercentage());
    }
}
