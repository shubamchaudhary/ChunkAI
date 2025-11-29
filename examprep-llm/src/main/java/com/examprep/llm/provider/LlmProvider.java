package com.examprep.llm.provider;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Supported LLM Providers - FREE TIER ONLY (No credit card required)
 */
@Getter
@RequiredArgsConstructor
public enum LlmProvider {
    
    GROQ(
        "Groq", 
        30,      // 30 requests per minute
        14400,   // 14,400 requests per day
        1,       // Highest priority (best free tier)
        "https://api.groq.com/openai/v1/chat/completions",
        "llama-3.3-70b-versatile"
    ),
    
    GEMINI(
        "Gemini", 
        10,      // 10 requests per minute
        1500,    // 1,500 requests per day
        2,
        "https://generativelanguage.googleapis.com/v1beta/models",
        "gemini-2.0-flash"
    ),
    
    COHERE(
        "Cohere", 
        20,      // 20 requests per minute (trial key)
        1000,    // ~1,000 requests per month on trial
        3,
        "https://api.cohere.ai/v1/chat",
        "command-r"
    ),
    
    CEREBRAS(
        "Cerebras",
        30,      // 30 requests per minute
        10000,   // Generous daily limit
        4,
        "https://api.cerebras.ai/v1/chat/completions",
        "llama3.1-70b"
    ),
    
    SAMBANOVA(
        "SambaNova",
        10,      // 10 requests per minute
        1000,    // Daily limit
        5,
        "https://api.sambanova.ai/v1/chat/completions",
        "Meta-Llama-3.1-70B-Instruct"
    );
    
    private final String displayName;
    private final int defaultRpm;
    private final int defaultRpd;
    private final int priority;
    private final String baseUrl;
    private final String defaultModel;
    
    public static LlmProvider fromString(String name) {
        for (LlmProvider provider : values()) {
            if (provider.name().equalsIgnoreCase(name) || 
                provider.getDisplayName().equalsIgnoreCase(name)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown provider: " + name);
    }
    
    public static int getTotalFreeRpm() {
        int total = 0;
        for (LlmProvider p : values()) {
            total += p.getDefaultRpm();
        }
        return total;
    }
}

