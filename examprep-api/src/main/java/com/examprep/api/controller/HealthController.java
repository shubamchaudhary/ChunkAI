package com.examprep.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Health and Keep-Alive Controller
 *
 * PREVENTING COLD STARTS ON RENDER FREE TIER:
 * ============================================
 * Render's free tier spins down after 15 minutes of inactivity.
 * Cold starts take 30-60+ seconds.
 *
 * Solution: Set up an external cron job to ping this endpoint every 10-14 minutes:
 *   - Use cron-job.org (free)
 *   - Use UptimeRobot (free)
 *   - Use GitHub Actions scheduled workflow
 *
 * Example cron: ping https://your-app.onrender.com/api/v1/health/ping every 10 min
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private static final Instant START_TIME = Instant.now();

    @Value("${spring.application.name:examprep-ai}")
    private String appName;

    /**
     * Simple ping endpoint - fastest possible response
     * Use this for keep-alive pings from external services
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }

    /**
     * Health check with basic status
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", appName,
            "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Detailed status for debugging (slightly slower)
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        Duration uptime = Duration.between(START_TIME, Instant.now());

        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", appName,
            "uptime", formatDuration(uptime),
            "uptimeSeconds", uptime.getSeconds(),
            "memory", Map.of(
                "used", formatBytes(usedMemory),
                "max", formatBytes(maxMemory),
                "usedPercent", Math.round((usedMemory * 100.0) / maxMemory)
            ),
            "jvm", Map.of(
                "version", System.getProperty("java.version"),
                "processors", runtime.availableProcessors()
            ),
            "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Warm-up endpoint - call this after deploy to pre-warm the JVM
     * Triggers lazy-loaded beans and JIT compilation
     */
    @GetMapping("/warmup")
    public ResponseEntity<Map<String, Object>> warmup() {
        long startTime = System.currentTimeMillis();

        // Force class loading and JIT compilation by accessing commonly used classes
        try {
            // Trigger database connection pool initialization
            Class.forName("org.postgresql.Driver");

            // Force some JIT compilation
            for (int i = 0; i < 1000; i++) {
                String.valueOf(i).hashCode();
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Warmup completed in {}ms", duration);

            return ResponseEntity.ok(Map.of(
                "status", "warmed",
                "durationMs", duration,
                "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            log.warn("Warmup error: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                "status", "partial",
                "error", e.getMessage(),
                "timestamp", Instant.now().toString()
            ));
        }
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
