package com.loglens.common.constants;

/**
 * Central registry of Kafka topic names so producers and consumers never
 * duplicate string literals. Topics themselves are declared as {@code NewTopic}
 * beans in the enrich configuration (Phase 3); Phase 1 relies on broker
 * auto-creation for {@link #LOG_INGEST_REQUESTS}.
 */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    /** Upload accepted → request ingest/chunking. Key = sessionId. */
    public static final String LOG_INGEST_REQUESTS = "log.ingest.requests";

    /**
     * Virtual parts (window-aligned byte ranges) of one staged file, produced by
     * the splitter and consumed in parallel. Key = {@code documentId:partIdx} so a
     * document's parts spread across partitions (never pinned to one).
     */
    public static final String LOG_INGEST_PARTS = "log.ingest.parts";

    /** Dead letters from the ingest lane (poison ingest requests). */
    public static final String LOG_INGEST_DLQ = "log.ingest.dlq";

    /** LLM enrichment/embedding work items. Partition == API-key slot. */
    public static final String LLM_ENRICH_REQUESTS = "llm.enrich.requests";

    /** 429-throttled work items, re-released to the main topic after a delay. */
    public static final String LLM_ENRICH_RETRY_60S = "llm.enrich.retry.60s";

    /** Dead letters from the enrichment lane (exhausted retries / poison work). */
    public static final String LLM_ENRICH_DLQ = "llm.enrich.dlq";
}
