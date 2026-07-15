package com.examprep.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Health Check Controller for monitoring and keep-alive functionality.
 *
 * This endpoint is publicly accessible (no authentication required) and is used by:
 * - Render.com health checks
 * - Internal keep-alive service to prevent free tier spin-down
 * - External monitoring services
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final DataSource dataSource;

    private static volatile long requestCount = 0;
    private static volatile Instant startTime = Instant.now();

    /**
     * Simple health check - lightweight, fast response
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        requestCount++;

        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("service", "examprep-ai");

        return ResponseEntity.ok(response);
    }

    /**
     * Detailed health check including database connectivity
     */
    @GetMapping("/detailed")
    public ResponseEntity<Map<String, Object>> detailedHealth() {
        requestCount++;

        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("service", "examprep-ai");
        response.put("uptime", getUptime());
        response.put("requestCount", requestCount);

        // Check database connectivity
        Map<String, Object> dbHealth = checkDatabaseHealth();
        response.put("database", dbHealth);

        // Overall status based on components
        if (!"UP".equals(dbHealth.get("status"))) {
            response.put("status", "DEGRADED");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Ping endpoint - minimal response for keep-alive
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        requestCount++;
        return ResponseEntity.ok("pong");
    }

    private Map<String, Object> checkDatabaseHealth() {
        Map<String, Object> dbHealth = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(5); // 5 second timeout
            long responseTime = System.currentTimeMillis() - startTime;

            dbHealth.put("status", valid ? "UP" : "DOWN");
            dbHealth.put("responseTimeMs", responseTime);
            dbHealth.put("database", connection.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            dbHealth.put("status", "DOWN");
            dbHealth.put("error", e.getMessage());
            dbHealth.put("responseTimeMs", System.currentTimeMillis() - startTime);
        }

        return dbHealth;
    }

    private String getUptime() {
        long seconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours, minutes, secs);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }
}
