package com.examprep.llm.config;

import com.examprep.llm.client.GeminiConfig;
import com.examprep.llm.keymanager.ApiKeyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class ApiKeyManagerConfig {
    
    private final GeminiConfig geminiConfig;
    
    @Bean
    public ApiKeyManager apiKeyManager() {
        List<String> apiKeys = geminiConfig.getAllApiKeys();
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("No Gemini API keys configured. Please set gemini.api-key or gemini.api-keys in application.properties");
        }
        return new ApiKeyManager(apiKeys);
    }
}

