package com.examprep.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Keep-Alive Service for Render.com Free Tier
 *
 * Render.com's free tier spins down services after ~15 minutes of inactivity.
 * This service periodically pings the health endpoint to keep the application alive.
 *
 * Configuration:
 * - keepalive.enabled: Enable/disable the keep-alive feature (default: false)
 * - keepalive.url: The URL to ping (default: uses internal health endpoint)
 * - keepalive.interval-ms: Ping interval in milliseconds (default: 840000 = 14 minutes)
 *
 * Note: This service only activates when keepalive.enabled=true
 */
@Service
@ConditionalOnProperty(name = "keepalive.enabled", havingValue = "true")
@Slf4j
public class KeepAliveService {

    private final HttpClient httpClient;
    private final String healthUrl;
    private final long intervalMs;

    private volatile Instant lastPingTime;
    private volatile int successCount = 0;
    private volatile int failureCount = 0;

    public KeepAliveService(
            @Value("${keepalive.url:}") String configuredUrl,
            @Value("${server.port:8080}") int serverPort,
            @Value("${keepalive.interval-ms:840000}") long intervalMs) {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Use configured URL or default to localhost health endpoint
        if (configuredUrl != null && !configuredUrl.isEmpty()) {
            this.healthUrl = configuredUrl;
        } else {
            this.healthUrl = "http://localhost:" + serverPort + "/api/v1/health/ping";
        }

        this.intervalMs = intervalMs;

        log.info("KeepAliveService initialized - URL: {}, Interval: {}ms ({}min)",
                healthUrl, intervalMs, intervalMs / 60000);
    }

    /**
     * Periodic health ping to prevent Render.com free tier spin-down.
     * Runs every 14 minutes by default (configurable via keepalive.interval-ms).
     *
     * Using fixedDelayString to support property placeholder for interval.
     */
    @Scheduled(fixedDelayString = "${keepalive.interval-ms:840000}", initialDelay = 60000)
    public void pingHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long responseTime = System.currentTimeMillis() - startTime;

            lastPingTime = Instant.now();

            if (response.statusCode() == 200) {
                successCount++;
                log.debug("Keep-alive ping successful - Status: {}, Response time: {}ms, Total success: {}",
                        response.statusCode(), responseTime, successCount);
            } else {
                failureCount++;
                log.warn("Keep-alive ping returned non-200 status: {} - Body: {}",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            failureCount++;
            log.error("Keep-alive ping failed: {} - Total failures: {}", e.getMessage(), failureCount);
        }
    }

    /**
     * Get keep-alive statistics
     */
    public KeepAliveStats getStats() {
        return new KeepAliveStats(healthUrl, intervalMs, lastPingTime, successCount, failureCount);
    }

    public record KeepAliveStats(
            String healthUrl,
            long intervalMs,
            Instant lastPingTime,
            int successCount,
            int failureCount
    ) {}
}
