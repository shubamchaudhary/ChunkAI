package com.deepdocai.enrich;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Fire-and-forget trigger for the Python LangGraph orchestrator (Graph 1).
 * Called once, by the single thread that wins the ENRICHING→CORRELATING flip,
 * to hand a fully-enriched session off for correlation and reporting.
 *
 * <p>Best-effort with a few short retries: if the orchestrator is unreachable we
 * log and move on rather than wedge the Kafka consumer — the session is already
 * durably CORRELATING and can be re-triggered.
 */
@Component
@Slf4j
public class OrchestratorClient {

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;

    public OrchestratorClient(@Value("${chunkai.orchestrator.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public void analyze(UUID sessionId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restClient.post()
                    .uri("/analyze/{sessionId}", sessionId)
                    .retrieve()
                    .toBodilessEntity();
                log.info("Triggered orchestrator /analyze/{}", sessionId);
                return;
            } catch (Exception e) {
                log.warn("orchestrator /analyze/{} attempt {}/{} failed: {}",
                    sessionId, attempt, MAX_ATTEMPTS, e.toString());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(500L * attempt);
                }
            }
        }
        log.error("Gave up triggering orchestrator for session {} after {} attempts "
            + "(session remains CORRELATING and can be re-triggered)", sessionId, MAX_ATTEMPTS);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
