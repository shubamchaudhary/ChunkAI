# LogLens — Partitioned Ingest Spec (split-manifest / virtual parts)

> **Agent-executable, one PR.** Read [current-design-walkthrough.md](current-design-walkthrough.md)
> first — it describes the system this spec modifies. Global decisions from
> [03-implementation-spec.md](03-implementation-spec.md) (manual ack, at-least-once,
> idempotent handlers, `loglens.*` config prefix, UTC, no tests) remain binding.
> Update [HANDOVER.md](HANDOVER.md) when done.

## Why (owner-approved redesign, 2026-07-17)

Current ingest loads the **whole file into heap** (`readAllLines`) and processes it as
ONE Kafka record — no parallelism, no partial resume, OOM on GB archives
(walkthrough §12 admits this). Target: **bounded memory O(one part) regardless of
file size**, parallel part processing, part-granular crash resume.

**Design: virtual parts (split manifest).** The file stays ONE object in blob.
A splitter makes one streaming pass recording **window-aligned byte ranges**;
Kafka carries tiny `{fileUrl, byteStart, byteEnd}` pointers; consumers do S3
**ranged GETs** for their slice. No physical part copies (zero extra blob I/O —
rejected alternative: re-writing K part-objects doubles storage + API calls).
Same idea as Hadoop/Spark input splits. Claim-check pattern throughout: Kafka
moves facts, blob moves bytes, Postgres holds truth.

```
 upload → B2 (1 object) → 1 msg log.ingest.requests
                              │
                    SPLITTER (streaming pass, O(1) heap):
                    window-aligned cuts every ~5MB
                    → documents.total_parts = K
                              │
                    K part-pointers → log.ingest.parts (6 partitions)
                              │ (parallel consumers, ranged GETs)
                    PART CONSUMER: chunk → parse → local anomaly flags
                    → ONE short TX {part-marker, chunks, metrics, parsed_parts++}
                              │ parsed_parts == total_parts (atomic claim)
                    FINALIZER: global p95 rule (SQL) → flag latency outliers
                    → enrich fan-out → ENRICHING → delete staged file
                              │
                    enrich/embed lanes: COMPLETELY UNCHANGED
```

## Message & topic

New record class `common/messages/IngestPartRequest`:
```java
{ UUID sessionId; UUID userId; UUID documentId; String fileUrl;
  int partIdx; int totalParts; long byteStart; long byteEndExclusive;
  long firstLineNumber; }
```
New topic `log.ingest.parts`: **6 partitions**, key = `documentId + ":" + partIdx`
(spreads one document's parts across partitions — do NOT key by documentId alone,
that would pin all parts to one partition and kill the parallelism this exists for).
DLQ: reuse `log.ingest.dlq`. Add the `NewTopic` bean in `KafkaTopicsConfig`.

## Schema changes (update schema-v2.sql AND apply to live DB via ALTER)

```sql
ALTER TABLE documents ADD COLUMN total_parts  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE documents ADD COLUMN parsed_parts INTEGER NOT NULL DEFAULT 0;

-- Exactly-once-EFFECT per part under at-least-once delivery: the marker insert
-- and all of the part's writes share one transaction; a redelivered part hits
-- the PK conflict and skips everything.
CREATE TABLE IF NOT EXISTS ingest_parts (
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    part_idx     INTEGER NOT NULL,
    processed_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (document_id, part_idx)
);
```

## Components

### 1. `ingest/IngestSplitter` (consumes existing `log.ingest.requests`; replaces IngestConsumer's whole-file entry)
- Set session `CHUNKING`. Stream the object from MinIO with a counting
  `BufferedInputStream` (track absolute byte offset of every line start; O(1) heap —
  never accumulate lines).
- Reuse `TimeWindowChunker`'s timestamp/record-start regexes to detect
  **window boundaries** (line whose bucket differs from the previous line's) and
  **record starts** (multi-line stack traces never straddle a cut).
- Emit a cut at the first window boundary after `loglens.ingest.part-target-bytes`
  (default `5242880`) bytes since the last cut. **No timestamps at all** → cut every
  `loglens.ingest.fallback-lines-per-part` (default 5000) lines at record starts.
- After the pass: `UPDATE documents SET total_parts=K, processing_status='PROCESSING'`,
  then produce the K `IngestPartRequest`s. (DB write BEFORE producing — the counter
  target must exist before the first part completes.)
- Failure → existing `KafkaFailureMarker` path (doc + session FAILED).

### 2. `ingest/PartConsumer` (new listener, group `ingest-part-workers`, topic `log.ingest.parts`)
- Ranged read: `MinioStorageService.openStream(fileUrl, byteStart, byteEndExclusive - byteStart)`
  — **add this overload** (MinIO client `GetObjectArgs.offset/length`; it's the same
  call with two extra args).
- Feed the stream to `TimeWindowChunker` (line numbers offset by `firstLineNumber`;
  fallback synthetic buckets derived deterministically from global line number so
  parts agree without coordination).
- Run all Layer-1 parsers per window. Apply **LOCAL anomaly rules only**
  (exception/OOM/deadlock lines, ≥5 WARNs). The latency>3×p95 rule is **deferred to
  the finalizer** — a part cannot know the corpus p95.
- **One short transaction**: `INSERT INTO ingest_parts ON CONFLICT DO NOTHING`;
  if no row inserted → redelivery → skip straight to ack. Else: batch-insert chunks
  into the session table, upsert `log_metrics`, `parsed_parts = parsed_parts + 1`.
  Commit, then ack. (Parsing and the ranged GET happen BEFORE the TX — keep it short,
  same discipline as everywhere else.)
- After commit: if `parsed_parts == total_parts` → invoke the finalizer (see next).
- Poison part (3 handler attempts) → `log.ingest.dlq` + doc/session FAILED (parsing
  failures are deterministic bugs, not quota weather — failing honestly beats a
  silent hole in the corpus).

### 3. `ingest/IngestFinalizer` (new; clones the `EnrichCompletion` atomic-claim pattern)
- Claim exactly once: `UPDATE documents SET processing_status='COMPLETED'
  WHERE id=? AND parsed_parts=total_parts AND processing_status='PROCESSING'` —
  only the caller that updates 1 row proceeds.
- **Global latency rule (replaces AnomalyDetector's in-memory two-pass):** compute
  corpus p95 per latency-type metric via SQL `percentile_cont(0.95)` over
  `log_metrics`, then `UPDATE log_chunks_s_<tid> SET is_anomalous=true` for chunks
  whose bucket's latency exceeds 3× that p95 (mirror the exact metric names
  `AnomalyDetector` uses today — read that class, keep behavior identical).
- `sessions.total_windows = total_windows + workItemCount` (**additive** — change
  `setTotalWindows` to an increment so multi-document sessions stay correct).
- Enrich fan-out: add an `EnrichProducer.produce` overload that reads chunk ids +
  `is_anomalous` from the session chunk table (`SessionChunkRepository` gains
  `listChunkRefs(sessionId, documentId)`) instead of taking `List<LogWindow>`.
- Session → `ENRICHING`; delete staged blob object; `staged_file_deleted=true`,
  `processed_at=now()`.

### 4. Deletions
- The whole-file path in `IngestConsumer` (`readAllLines`, in-memory window list,
  the in-memory p95 pass in `AnomalyDetector` — keep the class, its local rules and
  metric names are reused; delete only its corpus-p95 in-memory computation if it
  now lives in the finalizer's SQL). Nothing else moves; the enrich lane, budgets,
  retry topics, orchestrator, and query surface are untouched.

## Config
```properties
loglens.ingest.part-target-bytes=5242880
loglens.ingest.fallback-lines-per-part=5000
```

## SSE bonus (small, do it)
`AnalysisProgressController` already streams `{status, enriched, total}` — include
`parsedParts/totalParts` while status is `CHUNKING/PARSING` so big files show
ingest progress, not a frozen bar.

## Definition of done
1. Boot with `-Xmx256m`, upload a **100MB+** synthetic log: no OOM, heap flat
   (whole-file path would die — this is the proof of the redesign).
2. Kafka: K part records visible on `log.ingest.parts`, consumed across partitions
   in parallel (log lines from multiple partitions interleaved).
3. Re-deliver a part manually (kafka-console-producer the same JSON) → logs show
   "part already processed — skipping", no duplicate chunks, counters unchanged.
4. Finalizer fires exactly once; latency-outlier windows flagged; enrich lane runs
   unchanged; session reaches DONE; drilldown cites chunks with correct
   `line_start/line_end` (offsets survived the split).
5. Multi-document session: counters correct, total_windows accumulates.

## Interview-defense notes for this design (fold into docs/interview after implementing)
- **Why an event instead of polling the documents table for the URL?** The message
  is the wake-up, not the data. Poll-the-table = v1's deleted `ProcessingJobWorker`
  (3s latency floor, lease columns, stale-lock cleanup). The broker pushes,
  distributes to exactly one consumer, and remembers position across crashes.
- **Why pointers, not content, in Kafka?** Claim-check pattern: brokers are built
  for torrents of small records (~1MB default cap), not GB payloads; content-in-topic
  doubles storage, fights retention, can't be ranged-read.
- **Why virtual parts, not physical part files?** Identical parallelism/resume,
  minus one full copy of write I/O: split points are indexed, not copied — the
  Hadoop input-split idea. Physical parts' only win is console debuggability.
- **Why window-aligned cuts?** A blind byte split can halve a 60s window or a stack
  trace; cutting at window boundaries keeps every part self-contained, so parts need
  zero coordination.
- **Why is the p95 rule in a finalizer?** It's a corpus-global statistic; parts are
  deliberately independent. Local rules flag locally; one SQL percentile pass flags
  the rest. Two-phase stats — the same reason MapReduce has a reduce.
- **Why the ingest_parts marker table?** At-least-once redelivery + additive metric
  upserts would double-count; marker-in-the-same-TX gives exactly-once *effect* for
  a part at the cost of one tiny insert.
