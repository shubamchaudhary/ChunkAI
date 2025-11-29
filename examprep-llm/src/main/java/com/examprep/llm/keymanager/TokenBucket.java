package com.examprep.llm.keymanager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Token Bucket implementation for rate limiting.
 * 
 * How it works:
 * - Bucket has a capacity (max tokens)
 * - Tokens are consumed on each request
 * - Tokens refill at a constant rate
 * - If bucket is empty, request is rejected
 */
public class TokenBucket {
    
    private final int capacity;
    private final int refillAmount;
    private final long refillIntervalMs;
    
    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillTime;
    
    public TokenBucket(int capacity, int refillAmount, long refillIntervalMs) {
        this.capacity = capacity;
        this.refillAmount = refillAmount;
        this.refillIntervalMs = refillIntervalMs;
        this.availableTokens = new AtomicLong(capacity);
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
    }
    
    /**
     * Try to consume tokens from the bucket.
     * @param tokens Number of tokens to consume
     * @return true if successful, false if not enough tokens
     */
    public boolean tryConsume(int tokens) {
        refill();
        
        while (true) {
            long current = availableTokens.get();
            if (current < tokens) {
                return false;
            }
            
            if (availableTokens.compareAndSet(current, current - tokens)) {
                return true;
            }
            // CAS failed, retry
        }
    }
    
    /**
     * Refill tokens based on elapsed time.
     */
    public void refill() {
        long now = System.currentTimeMillis();
        long lastRefill = lastRefillTime.get();
        long elapsed = now - lastRefill;
        
        if (elapsed >= refillIntervalMs) {
            // Calculate how many refill periods have passed
            long periods = elapsed / refillIntervalMs;
            long tokensToAdd = periods * refillAmount;
            
            if (lastRefillTime.compareAndSet(lastRefill, lastRefill + (periods * refillIntervalMs))) {
                // Add tokens, but don't exceed capacity
                while (true) {
                    long current = availableTokens.get();
                    long newValue = Math.min(capacity, current + tokensToAdd);
                    if (availableTokens.compareAndSet(current, newValue)) {
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * Get milliseconds until at least one token is available.
     */
    public long getMillisUntilNextToken() {
        refill();
        
        if (availableTokens.get() > 0) {
            return 0;
        }
        
        long elapsed = System.currentTimeMillis() - lastRefillTime.get();
        return Math.max(0, refillIntervalMs - elapsed);
    }
    
    public long getAvailableTokens() {
        refill();
        return availableTokens.get();
    }
}

