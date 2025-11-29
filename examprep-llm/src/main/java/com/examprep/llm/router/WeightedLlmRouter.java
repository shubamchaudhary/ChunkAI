package com.examprep.llm.router;

import com.examprep.llm.provider.LlmProvider;
import com.examprep.llm.provider.ProviderClient;
import com.examprep.llm.provider.ProviderClient.ProviderException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WeightedLlmRouter {
    
    private final Map<LlmProvider, ProviderClient> providerClients;
    private final List<ProviderSlot> weightedSlots = new CopyOnWriteArrayList<>();
    private final Map<LlmProvider, ProviderState> providerStates = new ConcurrentHashMap<>();
    
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    @Value("${llm.router.max-retries:5}")
    private int maxRetries;
    
    @Value("${llm.router.cooldown-ms:60000}")
    private long cooldownMs;
    
    @Value("${llm.router.retry-delay-ms:2000}")
    private long retryDelayMs;
    
    @Value("${llm.groq.api-key:${LLM_GROQ_API_KEY:}}")
    private String groqApiKey;
    @Value("${llm.groq.rpm:30}")
    private int groqRpm;
    
    @Value("${llm.gemini.api-key:${LLM_GEMINI_API_KEY:}}")
    private String geminiApiKey;
    @Value("${llm.gemini.rpm:10}")
    private int geminiRpm;
    
    @Value("${llm.cohere.api-key:${LLM_COHERE_API_KEY:}}")
    private String cohereApiKey;
    @Value("${llm.cohere.rpm:20}")
    private int cohereRpm;
    
    @Value("${llm.cerebras.api-key:${LLM_CEREBRAS_API_KEY:}}")
    private String cerebrasApiKey;
    @Value("${llm.cerebras.rpm:30}")
    private int cerebrasRpm;
    
    @Value("${llm.sambanova.api-key:${LLM_SAMBANOVA_API_KEY:}}")
    private String sambanovaApiKey;
    @Value("${llm.sambanova.rpm:10}")
    private int sambanovaRpm;
    
    public WeightedLlmRouter(List<ProviderClient> clients) {
        this.providerClients = clients.stream()
            .collect(Collectors.toMap(ProviderClient::getProvider, c -> c));
    }
    
    @PostConstruct
    public void initialize() {
        registerProvider(LlmProvider.GROQ, groqApiKey, groqRpm);
        registerProvider(LlmProvider.CEREBRAS, cerebrasApiKey, cerebrasRpm);
        registerProvider(LlmProvider.COHERE, cohereApiKey, cohereRpm);
        registerProvider(LlmProvider.GEMINI, geminiApiKey, geminiRpm);
        registerProvider(LlmProvider.SAMBANOVA, sambanovaApiKey, sambanovaRpm);
        
        if (providerStates.isEmpty()) {
            log.warn("No LLM providers configured! Defaulting to Gemini only.");
            // Try to use existing Gemini API key from application.properties
            String fallbackGeminiKey = System.getenv("GEMINI_API_KEY");
            if (fallbackGeminiKey == null || fallbackGeminiKey.isEmpty()) {
                fallbackGeminiKey = geminiApiKey;
            }
            if (fallbackGeminiKey != null && !fallbackGeminiKey.isEmpty()) {
                registerProvider(LlmProvider.GEMINI, fallbackGeminiKey, 8); // Conservative limit
                log.info("Using fallback Gemini API key from environment/config");
            }
        }
        
        if (providerStates.isEmpty()) {
            throw new IllegalStateException("No LLM providers configured! Please set at least one API key.");
        }
        
        buildWeightedSlots();
        scheduler.scheduleAtFixedRate(this::healthCheck, 60, 60, TimeUnit.SECONDS);
        
        int totalRpm = weightedSlots.size();
        log.info("[ROUTER] Initialized successfully | totalProviders={} | totalRpm={} | providers={}", 
            providerStates.size(), totalRpm,
            providerStates.keySet().stream()
                .map(LlmProvider::getDisplayName)
                .collect(java.util.stream.Collectors.joining(", ")));
        
        // Log detailed provider info
        for (ProviderState state : providerStates.values()) {
            log.info("[ROUTER] Provider registered | provider={} | rpm={} | priority={} | model={}", 
                state.getProvider().getDisplayName(), state.getRpm(), 
                state.getProvider().getPriority(), state.getProvider().getDefaultModel());
        }
    }
    
    private void registerProvider(LlmProvider provider, String apiKey, int rpm) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Skipping {} - no API key provided", provider.getDisplayName());
            return;
        }
        if (!providerClients.containsKey(provider)) {
            log.debug("Skipping {} - no client implementation found", provider.getDisplayName());
            return;
        }
        providerStates.put(provider, new ProviderState(provider, apiKey, rpm));
        log.info("Registered: {} ({} RPM)", provider.getDisplayName(), rpm);
    }
    
    private void buildWeightedSlots() {
        weightedSlots.clear();
        List<ProviderSlot> slots = new ArrayList<>();
        for (ProviderState state : providerStates.values()) {
            for (int i = 0; i < state.getRpm(); i++) {
                slots.add(new ProviderSlot(state.getProvider()));
            }
        }
        Collections.shuffle(slots);
        weightedSlots.addAll(slots);
        log.info("Built {} weighted slots across {} providers", slots.size(), providerStates.size());
    }
    
    public String generateContent(String prompt) throws LlmRouterException {
        return generateContent(prompt, null);
    }
    
    public String generateContent(String prompt, String modelOverride) throws LlmRouterException {
        long requestStartTime = System.currentTimeMillis();
        String requestId = "req-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        
        log.info("[ROUTER] Starting LLM request | requestId={} | promptLength={} | modelOverride={} | availableProviders={}", 
            requestId, prompt.length(), modelOverride != null ? modelOverride : "default", providerStates.size());
        
        Set<LlmProvider> attemptedProviders = new LinkedHashSet<>();
        ProviderException lastException = null;
        
        for (int attempt = 0; attempt < maxRetries && attemptedProviders.size() < providerStates.size(); attempt++) {
            LlmProvider provider = selectNextProvider(attemptedProviders);
            
            if (provider == null) {
                log.warn("[ROUTER] No available provider | requestId={} | attempt={} | attemptedProviders={}", 
                    requestId, attempt + 1, attemptedProviders.size());
                
                if (attempt < maxRetries - 1) {
                    long waitTime = Math.min(retryDelayMs * (1L << attempt), 30_000);
                    log.info("[ROUTER] Waiting before retry | requestId={} | waitMs={}", requestId, waitTime);
                    sleep(waitTime);
                    attemptedProviders.clear();
                    continue;
                }
                break;
            }
            
            attemptedProviders.add(provider);
            ProviderState state = providerStates.get(provider);
            int requestsThisMinute = state != null ? state.getRequestsThisMinute() : 0;
            
            log.info("[ROUTER] Attempting provider | requestId={} | attempt={}/{} | provider={} | rpm={} | usedThisMinute={} | available={} | model={}", 
                requestId, attempt + 1, maxRetries, provider.getDisplayName(), 
                state != null ? state.getRpm() : 0, requestsThisMinute,
                state != null && state.isAvailable(), modelOverride != null ? modelOverride : provider.getDefaultModel());
            
            long providerStartTime = System.currentTimeMillis();
            
            try {
                String result = executeRequest(provider, prompt, modelOverride);
                long providerDuration = System.currentTimeMillis() - providerStartTime;
                long totalDuration = System.currentTimeMillis() - requestStartTime;
                
                recordSuccess(provider);
                
                log.info("[ROUTER] Request succeeded | requestId={} | provider={} | providerDurationMs={} | totalDurationMs={} | responseLength={} | attempts={}", 
                    requestId, provider.getDisplayName(), providerDuration, totalDuration, 
                    result != null ? result.length() : 0, attempt + 1);
                
                return result;
            } catch (ProviderException e) {
                long providerDuration = System.currentTimeMillis() - providerStartTime;
                lastException = e;
                recordFailure(provider, e);
                
                // Determine failure reason for better logging
                String failureReason = determineFailureReason(e);
                
                log.warn("[ROUTER] Provider request failed | requestId={} | provider={} | attempt={}/{} | statusCode={} | reason={} | rateLimited={} | retryable={} | durationMs={} | error={}", 
                    requestId, provider.getDisplayName(), attempt + 1, maxRetries, 
                    e.getStatusCode(), failureReason, e.isRateLimited(), e.isRetryable(), providerDuration, e.getMessage());
                
                if (e.isRateLimited()) {
                    log.info("[ROUTER] Rate limit hit, backing off | requestId={} | provider={}", requestId, provider.getDisplayName());
                    sleep(1000);
                } else if (e.getStatusCode() == 413) {
                    log.info("[ROUTER] Payload too large - skipping provider for remaining attempts | requestId={} | provider={}", requestId, provider.getDisplayName());
                } else if (e.getStatusCode() == 410) {
                    log.info("[ROUTER] Service unavailable (410 Gone) - skipping provider for remaining attempts | requestId={} | provider={}", requestId, provider.getDisplayName());
                }
            }
        }
        
        long totalDuration = System.currentTimeMillis() - requestStartTime;
        
        log.error("[ROUTER] All providers failed | requestId={} | totalAttempts={} | attemptedProviders={} | totalDurationMs={} | lastError={}", 
            requestId, attemptedProviders.size(), 
            attemptedProviders.stream().map(LlmProvider::getDisplayName).collect(java.util.stream.Collectors.joining(",")),
            totalDuration, lastException != null ? lastException.getMessage() : "unknown");
        
        throw new LlmRouterException(
            "All providers failed after " + attemptedProviders.size() + " attempts",
            new ArrayList<>(attemptedProviders),
            lastException
        );
    }
    
    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    
    private String determineFailureReason(ProviderClient.ProviderException e) {
        int status = e.getStatusCode();
        if (status == 413) return "PayloadTooLarge";
        if (status == 410) return "ServiceUnavailable";
        if (status == 429) return "RateLimited";
        if (status == 401 || status == 403) return "AuthError";
        if (status >= 500) return "ServerError";
        return "ClientError";
    }
    
    private LlmProvider selectNextProvider(Set<LlmProvider> excludeProviders) {
        if (weightedSlots.isEmpty()) {
            log.debug("[ROUTER] No weighted slots available");
            return null;
        }
        
        int startIndex = roundRobinIndex.getAndIncrement() % weightedSlots.size();
        
        for (int i = 0; i < weightedSlots.size(); i++) {
            int index = (startIndex + i) % weightedSlots.size();
            LlmProvider provider = weightedSlots.get(index).getProvider();
            
            if (excludeProviders.contains(provider)) {
                log.debug("[ROUTER] Skipping excluded provider | provider={}", provider.getDisplayName());
                continue;
            }
            
            ProviderState state = providerStates.get(provider);
            if (state != null && state.isAvailable() && state.hasCapacity()) {
                log.debug("[ROUTER] Selected provider from round-robin | provider={} | slotIndex={} | requestsThisMinute={}/{}", 
                    provider.getDisplayName(), index, state.getRequestsThisMinute(), state.getRpm());
                return provider;
            } else {
                log.debug("[ROUTER] Provider not available or at capacity | provider={} | available={} | hasCapacity={} | requestsThisMinute={}/{}", 
                    provider.getDisplayName(), 
                    state != null && state.isAvailable(), 
                    state != null && state.hasCapacity(),
                    state != null ? state.getRequestsThisMinute() : 0,
                    state != null ? state.getRpm() : 0);
            }
        }
        
        log.debug("[ROUTER] No available provider found after checking all slots | excludedProviders={}", 
            excludeProviders.size());
        return null;
    }
    
    private String executeRequest(LlmProvider provider, String prompt, String model) throws ProviderException {
        ProviderClient client = providerClients.get(provider);
        ProviderState state = providerStates.get(provider);
        
        if (!state.tryAcquire()) {
            log.warn("[ROUTER] Rate limit check failed before request | provider={} | requestsThisMinute={}/{}", 
                provider.getDisplayName(), state.getRequestsThisMinute(), state.getRpm());
            throw new ProviderException("Rate limit exceeded", provider, 429, true);
        }
        
        log.debug("[ROUTER] Executing provider request | provider={} | promptLength={} | model={} | requestsThisMinute={}/{}", 
            provider.getDisplayName(), prompt.length(), model != null ? model : "default", 
            state.getRequestsThisMinute(), state.getRpm());
        
        try {
            return client.generateContent(prompt, state.getApiKey(), model);
        } catch (ProviderException e) {
            log.debug("[ROUTER] Provider request threw exception | provider={} | statusCode={} | error={}", 
                provider.getDisplayName(), e.getStatusCode(), e.getMessage());
            throw e;
        }
    }
    
    private void recordSuccess(LlmProvider provider) {
        ProviderState state = providerStates.get(provider);
        if (state != null) {
            state.recordSuccess();
            log.debug("[ROUTER] Recorded success | provider={} | totalRequests={} | requestsThisMinute={}/{}", 
                provider.getDisplayName(), state.getTotalRequests(), state.getRequestsThisMinute(), state.getRpm());
        }
    }
    
    private void recordFailure(LlmProvider provider, ProviderException error) {
        ProviderState state = providerStates.get(provider);
        if (state != null) {
            state.recordFailure(error);
            log.debug("[ROUTER] Recorded failure | provider={} | consecutiveFailures={} | statusCode={} | rateLimited={}", 
                provider.getDisplayName(), state.getConsecutiveFailures(), 
                error.getStatusCode(), error.isRateLimited());
        }
    }
    
    private void healthCheck() {
        log.debug("[ROUTER] Running health check - resetting minute counters");
        int totalRequests = 0;
        for (ProviderState state : providerStates.values()) {
            totalRequests += state.getRequestsThisMinute();
            state.resetMinuteCounter();
        }
        log.info("[ROUTER] Health check completed | totalRequestsLastMinute={} | providers={}", 
            totalRequests, providerStates.size());
    }
    
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProviders", providerStates.size());
        stats.put("totalRpm", weightedSlots.size());
        
        Map<String, Object> providerStats = new LinkedHashMap<>();
        for (ProviderState state : providerStates.values()) {
            providerStats.put(state.getProvider().getDisplayName(), Map.of(
                "rpm", state.getRpm(),
                "requestsThisMinute", state.getRequestsThisMinute(),
                "healthy", state.isAvailable()
            ));
        }
        stats.put("providers", providerStats);
        return stats;
    }
    
    @Getter
    private static class ProviderSlot {
        private final LlmProvider provider;
        ProviderSlot(LlmProvider provider) { this.provider = provider; }
    }
    
    @Getter
    public static class ProviderState {
        private final LlmProvider provider;
        private final String apiKey;
        private final int rpm;
        
        private final AtomicInteger requestsThisMinute = new AtomicInteger(0);
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        
        ProviderState(LlmProvider provider, String apiKey, int rpm) {
            this.provider = provider;
            this.apiKey = apiKey;
            this.rpm = rpm;
        }
        
        boolean tryAcquire() {
            if (requestsThisMinute.get() >= rpm) return false;
            requestsThisMinute.incrementAndGet();
            totalRequests.incrementAndGet();
            return true;
        }
        
        boolean hasCapacity() { return requestsThisMinute.get() < rpm; }
        
        boolean isAvailable() {
            if (consecutiveFailures.get() >= 5) {
                if (System.currentTimeMillis() - lastFailureTime.get() < 120_000) return false;
                consecutiveFailures.set(0);
            }
            return true;
        }
        
        void recordSuccess() { consecutiveFailures.set(0); }
        
        void recordFailure(ProviderException error) {
            consecutiveFailures.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            if (error.isRateLimited()) requestsThisMinute.set(rpm);
        }
        
        void resetMinuteCounter() { requestsThisMinute.set(0); }
        int getRequestsThisMinute() { return requestsThisMinute.get(); }
    }
    
    public static class LlmRouterException extends RuntimeException {
        @Getter private final List<LlmProvider> attemptedProviders;
        @Getter private final ProviderException lastError;
        
        public LlmRouterException(String message, List<LlmProvider> attempted, ProviderException lastError) {
            super(message);
            this.attemptedProviders = attempted;
            this.lastError = lastError;
        }
    }
}

