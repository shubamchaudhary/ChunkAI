# LogLens v2 — Implementation Spec (code-level, agent-executable)

> **How to use this file:** each phase below is a self-contained coding task sized for one PR. Execute phases **in order** — each leaves the app buildable and runnable. Hand a phase to a coding agent verbatim (`gh` + agent of choice); the phase text + this header + [02-target-architecture.md](02-target-architecture.md) is the full context needed. **Skip writing tests** (owner's call — a separate pass will add them). Update [HANDOVER.md](HANDOVER.md) after each phase lands.

## Global decisions (binding for every phase)

1. **v1 doc-QA mode is DELETED, not kept.** Owner decision: focus the product; git history preserves the old code. The deletion list per phase is part of the task.
2. Schema: [/schema-v2.sql](../schema-v2.sql) applied to a **fresh database** (drop v1 tables; keep `users` shape — it's identical). `init.sql` is replaced by `schema-v2.sql` in docker-compose.
3. `sessions` replaces `chats` everywhere: entity `Session`, repo `SessionRepository`, REST path `/api/v1/sessions`.
4. All new backend code in existing module `loglens-backend`, package roots:
   - `com.loglens.ingest` — chunker, parsers, anomaly detection
   - `com.loglens.enrich` — Kafka LLM lanes, findings
   - `com.loglens.analysis` — status, completion trigger, query APIs, SSE
   - `com.loglens.storage` — MinIO staging + per-session chunk tables
   - keep: `api` (auth/security), `data` (entities/repos), `llm` (GeminiClient), `common`
5. **Keep and reuse:** `GeminiClient`, `ApiKeyManager` (key list + per-key pacing intervals), JWT security stack, `GlobalExceptionHandler`, WebClient config.
6. Kafka client: `spring-kafka`. Topics auto-created via `KafkaAdmin` `NewTopic` beans. JSON payloads via Jackson (`JsonSerializer`/`JsonDeserializer`, type headers off, explicit target types).
7. Consumers: **manual ack** (`AckMode.MANUAL`), commit after DB write → at-least-once. Every handler idempotent (natural keys in schema-v2 make this free).
8. Timestamps in UTC everywhere; time windows default **60s** (`loglens.window-seconds=60`).
9. Config prefix for all new keys: `loglens.*`. Keep existing `gemini.*` keys.
10. Errors: any unexpected consumer exception → log + nack with backoff (Spring `DefaultErrorHandler`, 3 attempts) → then produce raw record to the relevant `.dlq` topic and ack.

## Message schemas (JSON, one class each in `com.loglens.common.messages`)

```java
IngestRequest      { UUID sessionId; UUID userId; UUID documentId; String fileUrl; }
EnrichRequest      { UUID workId; UUID sessionId; String kind;      // ENRICH_WINDOW | EMBED_BATCH
                     List<UUID> chunkIds; int attempt; long notBeforeEpochMs; }
```

## Kafka topics (created in `enrich` config)

| Topic | Partitions | Notes |
|---|---|---|
| `log.ingest.requests` | 6 | key = sessionId |
| `llm.enrich.requests` | **= gemini key count** (from `ApiKeyManager.getSlotCount()`) | producer uses round-robin partitioner (lag-aware = later optimization) |
| `llm.enrich.retry.60s` | same | delayed redelivery |
| `llm.enrich.dlq`, `log.ingest.dlq` | 1 | dead letters |

---

## PHASE 0 — Infrastructure & schema baseline

**docker-compose.yml**: keep postgres (swap `init.sql` mount → `schema-v2.sql`); add:
- `kafka`: `apache/kafka:latest` single-node KRaft (`KAFKA_NODE_ID=1`, combined broker+controller, `PLAINTEXT://localhost:9092`, auto-create topics on).
- `minio`: `minio/minio`, `server /data --console-address :9001`, ports 9000/9001, root user `minioadmin`/`minioadmin123`, named volume.

**build.gradle.kts** (loglens-backend): add `org.springframework.kafka:spring-kafka`, `io.minio:minio:8.5.x`.

**application.properties** additions:
```properties
spring.kafka.bootstrap-servers=localhost:9092
loglens.window-seconds=60
loglens.embedding.enabled=true
loglens.embedding.batch-size=80
loglens.minio.endpoint=http://localhost:9000
loglens.minio.access-key=minioadmin
loglens.minio.secret-key=minioadmin123
loglens.minio.bucket=staging
loglens.orchestrator.url=http://localhost:8000
```

**Definition of done:** `docker compose up -d` starts postgres+kafka+minio healthy; app boots against fresh schema-v2 DB.

## PHASE 1 — Sessions, MinIO staging, upload → Kafka

**New:**
- `data/entity/Session.java` + `SessionRepository` (matches schema-v2 `sessions`; includes `@Modifying` queries: `setStatus`, `incrementEnrichedWindows`, `setTotalWindows`).
- Rewrite `Document` entity to schema-v2 shape (`sessionId`, `fileUrl`, `stagedFileDeleted`, ...).
- `storage/MinioStorageService implements FileStorageService` — `store(sessionId, documentId, InputStream) → fileUrl`, `openStream(fileUrl)`, `delete(fileUrl)`, bucket auto-create on startup.
- `storage/SessionChunkTableManager` — `createFor(UUID sessionId)`, `dropFor(UUID sessionId)`, `tableName(UUID)` (UUID → `log_chunks_s_<uuid with _>`; validate input is a real UUID before splicing — injection guard). Executes the DDL template from schema-v2.sql via `JdbcTemplate`.
- `api/controller/SessionController` — CRUD for sessions (`POST /api/v1/sessions` creates row + chunk table; `DELETE` drops table + cascades).
- Rework `DocumentController`: upload = validate → store to MinIO → insert `documents` row → **produce `IngestRequest` to `log.ingest.requests`** → 202 with documentId. Duplicate check via `uq_doc_per_session` (catch constraint violation → return existing).

**Delete:** `ProcessingJobWorker`, `ProcessingJob` entity + repository, `SchedulingConfig` (if only used for the worker), `LocalFileStorageService`, `ChatController`/`Chat` entity/`ChatRepository` (replaced by Session).

**Definition of done:** upload lands file in MinIO (visible in console :9001), row in `documents`, message on topic (verify `kafka-console-consumer`), session create/delete manages its chunk table.

## PHASE 2 — Chunker + Layer-1 parsers + anomaly flag

**New in `ingest/`:**
- `IngestConsumer` — `@KafkaListener(topics=log.ingest.requests, groupId=ingest-workers)`. Flow: set session `CHUNKING` → stream file from MinIO **line by line** (never whole file) → `TimeWindowChunker` → batch-insert chunks (JDBC batch, 500/insert) into the session's table → set `PARSING` → run parsers per window → upsert `log_metrics` → mark anomalous windows (`is_anomalous=true`) → set `total_windows` → produce `EnrichRequest`s (see Phase 3 producer) → set `ENRICHING` → delete staged file + `staged_file_deleted=true`, `processed_at=now()`.
- `TimeWindowChunker` — groups lines into windows by parsed timestamp (`loglens.window-seconds`). Timestamp extraction: try configurable regex list (ISO-8601, `yyyy-MM-dd HH:mm:ss`, syslog). Lines without timestamps join the current window. **No timestamps at all → fallback: 500-line windows, synthetic buckets from file order.**
- `parser/LogWindowParser` interface: `List<MetricRow> parse(Window w)` + `boolean isAnomalous(Window w)` contribution.
- Implementations (regex-based, one class each): `ApiCallParser` (endpoint counts + latency ms extraction), `SqlParser` (query counts, failures/timeouts/deadlocks), `ErrorParser` (exception names, stack-trace fingerprint = SHA-256 of exception class + top 3 frames), `LifecycleParser` (restart/deploy/config markers), `PerformanceParser` (GC pause, thread pool, OOM warnings), `AuthParser` (401/403), `TrafficParser` (line/request volume per bucket).
- `AnomalyDetector` — window is anomalous if: any ERROR/FATAL/exception line, WARN count ≥ 5, or any parser latency > p95 × 3 (computed after full pass; two-pass over windows is fine).
- `MetricsWriter` — upserts `log_metrics` (`ON CONFLICT ... DO UPDATE SET count = log_metrics.count + EXCLUDED.count, ...`).

**Delete:** `core/processor/**` (Pdf/Ppt/Image/Text processors + factory), `ChunkingService`, `DocumentProcessingService`, `core/model/*`, `DocumentChunk` entity + repositories (per-session tables are JDBC-only), `TokenCounter` if now unused.

**Definition of done:** uploading a sample log file produces: populated `log_chunks_s_{id}` with correct windows, `log_metrics` rows with believable counts, `is_anomalous` set, session status reaches `ENRICHING`, staged file gone from MinIO.

## PHASE 3 — LLM lanes: enrichment + embeddings over Kafka

**New in `enrich/`:**
- `KafkaTopicsConfig` — the `NewTopic` beans (partition count for `llm.enrich.requests` from `ApiKeyManager.getSlotCount()`).
- `EnrichProducer` — sends `EnrichRequest`s: one `ENRICH_WINDOW` per anomalous window; `EMBED_BATCH` per 80 chunks (all windows) when `loglens.embedding.enabled`.
- `EnrichConsumer` — `@KafkaListener(topics=llm.enrich.requests, groupId=llm-workers, concurrency = <slot count>)`, `AckMode.MANUAL`. Per record: `apiKey = apiKeyManager.getApiKey(record.partition())`; enforce per-partition pacing (map partition → lastCallMs; sleep to honor 7500ms — reuse `ApiKeySlot` logic keyed by partition). Dispatch by kind:
  - `ENRICH_WINDOW`: load window content from chunk table → prompt (below) → `GeminiClient.generateContent` with **JSON response** → parse into finding(s) → upsert `log_findings` on `(session_id, fingerprint)` (`occurrence_count++`, merge evidence ids) → `incrementEnrichedWindows`.
  - `EMBED_BATCH`: `GeminiClient.batchGenerateEmbeddings` → `UPDATE log_chunks_s_{id} SET embedding=... WHERE chunk_id=...` (JDBC batch).
  - On `RateLimitException` (429): produce same record with `attempt+1`, `notBeforeEpochMs = now+60_000` to `llm.enrich.retry.60s`, ack, continue (thread never sleeps 60s).
  - `attempt >= 3` → `llm.enrich.dlq` + ack; ENRICH failures also `incrementEnrichedWindows` (a failed window must not wedge completion).
- `RetryConsumer` — single-threaded listener on the retry topic: if `notBefore` in future, `Thread.sleep(remaining)` (dedicated container, this is its whole job), then re-produce to `llm.enrich.requests`.
- Enrichment prompt (constant in `enrich/EnrichPrompts.java`): system = "You are a production-log analyst..."; user = window content + parser metric context; **required JSON output** `{category, severity, title, explanation, confidence}` — chunk ids attached by code, not the model. Fingerprint = SHA-256(category + normalized title).
- Completion check: after every `incrementEnrichedWindows`, if `enriched_windows >= total_windows` → atomically flip status `ENRICHING→CORRELATING` (`UPDATE ... WHERE analysis_status='ENRICHING'` — the one row that wins triggers) → HTTP POST `{orchestrator.url}/analyze/{sessionId}` (fire-and-forget with retry ×3).

**Delete:** `KeyedWorkerPool`, `WorkItem`, `BatchEmbeddingService`, `RecursiveSummarizationService`, `EmbeddingService`, `RagService`, `QueryController` (old), `ExamAnswerPrompts`, `DocumentAnalysisPrompts` (replaced by `EnrichPrompts`), `QueryHistory` entity/repos. Keep `ApiKeySlot` only if reused for pacing, else fold into consumer.

**Definition of done:** end-to-end upload → findings appear in `log_findings` with deduped fingerprints; 429s visible flowing through retry topic without stalling consumers; embeddings populate; session flips to `CORRELATING` exactly once.

## PHASE 4 — LangGraph orchestrator (Python) + report

**New top-level dir `rag-orchestrator/`** (FastAPI + langgraph + langchain-google-genai + psycopg[binary]; `requirements.txt`; `Dockerfile`; runs on :8000; env: `DATABASE_URL`, `GEMINI_API_KEY` — one dedicated key):
- `POST /analyze/{session_id}` → run **Graph 1** async, return 202.
  Graph 1 nodes: `load` (findings+metrics from PG) → `cluster` (pure python: group findings by overlapping time_range + shared category; no LLM) → `correlate` (per cluster: LLM → narrative + root_cause, JSON out) → `ground_check` (LLM judge: every claim supported by finding titles/evidence? fail → regenerate, max 2) → `write_incidents` → `compose_report` (LLM over incidents + top metrics → markdown + json) → `save_report` → set session `REPORTING`→`DONE`. Any node failure after retries → session `FAILED` + error_message.
- `POST /drilldown` `{session_id, question}` → **Graph 2**: `retrieve` (SQL against `log_chunks_s_{id}`: pgvector kNN via embedded question — embed with Gemini — UNION GIN `to_tsvector @@ websearch_to_tsquery`, take 30) → `grade` (LLM: keep relevant) → weak? `rewrite` question (max 2 loops) → `generate` (grounded answer, cite chunk_ids/line ranges) → return `{answer, citations[]}`.
- Status updates written directly to PG (`sessions.analysis_status`) — Java side owns nothing here.

**docker-compose**: add `rag-orchestrator` service (build context, depends_on postgres).

**Definition of done:** after Phase-3 completion trigger, `incidents` + `reports` rows exist and session hits `DONE`; `/drilldown` returns a cited answer for a seeded session.

## PHASE 5 — Query APIs + SSE progress

**New in `analysis/`:**
- `AnalysisQueryController`:
  - `GET /api/v1/sessions/{id}/metrics?category=&metric=&from=&to=` → rows from `log_metrics`
  - `GET /api/v1/sessions/{id}/findings?category=&severity=` → `log_findings`
  - `GET /api/v1/sessions/{id}/incidents` → `incidents`
  - `GET /api/v1/sessions/{id}/report` → `reports.content_md`
  - `GET /api/v1/sessions/{id}/evidence?chunkIds=` → rows from the session chunk table
  - `POST /api/v1/sessions/{id}/drilldown {question}` → proxy to orchestrator `/drilldown`
  - every endpoint verifies `session.user_id == jwt user` (403 otherwise)
- `AnalysisProgressController` — `GET /api/v1/sessions/{id}/progress` returns `SseEmitter`; a single `@Scheduled(fixedDelay=1500)` poller reads status rows for sessions with open emitters and pushes `{status, enriched, total}`; completes emitter on `DONE`/`FAILED`.

**Definition of done:** full happy path via curl: create session → upload → watch SSE → query metrics/findings/incidents/report → drilldown answer with citations.

---

## Order of deletions safety note
Each phase's deletions happen **in that phase**, after its replacement works in the same branch. Nothing is commented out; delete outright (git history is the archive).

## Out of scope (explicitly)
Tests (separate pass, cheaper model), frontend (after backend), lag-aware partitioner, golden-corpus regression set, Flyway (schema-v2.sql applied manually for now), auth hardening beyond existing JWT.
