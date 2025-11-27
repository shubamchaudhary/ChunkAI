package com.examprep.llm.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gemini")
@Getter
@Setter
public class GeminiConfig {
    private String apiKey;
    private String embeddingModel = "text-embedding-004";
    private String generationModel = "gemini-2.5-flash";
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    private int maxRetries = 3;
    private int timeoutSeconds = 30;
}

