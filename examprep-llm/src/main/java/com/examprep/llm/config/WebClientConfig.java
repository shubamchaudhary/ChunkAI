package com.examprep.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClient Configuration for LLM API calls
 *
 * Key configurations:
 * - Increased buffer size (16MB) for large embedding responses
 * - Connection timeouts for reliability
 */
@Configuration
public class WebClientConfig {

    // 16MB buffer - Gemini batch embedding responses can be large
    // (20 embeddings × 768 dimensions × JSON overhead ≈ 1-2MB)
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    @Bean
    public WebClient.Builder webClientBuilder() {
        // Configure larger buffer for API responses
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();

        // Configure HTTP client with timeouts
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(60))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);

        return WebClient.builder()
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
