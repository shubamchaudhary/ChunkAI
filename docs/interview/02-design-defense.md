# 02 — Design Defense (with numbers)

For each decision: **the problem → why this solution → the alternative rejected →
the number that proves it.** Rehearse these as "problem-first" answers, never
"technology-first."

> Scale note used throughout: a **corpus** is the *design target* of 10⁵–10⁶
> chunks (an incident archive or a service-day). Your portfolio demo runs
> smaller corpora — say so honestly, and say the design is *built for* the large
> case. Never pretend the demo processes a million chunks.

---

## 1. Why Kafka? (the one they'll push hardest on)

**Problem.** Enrichment is a **bursty producer vs. rate-capped consumer**
mismatch. Parsing a corpus flags thousands of anomalous windows *at once*, but
each LLM API key is capped (Groq ~30 RPM; Gemini free embeddings ~100 RPM /
30K TPM / 1K requests-per-day **per key**). You cannot fire 3,000 calls into a
30-RPM key — you need a durable buffer that absorbs the burst and paces the
drain.

**Why Kafka specifically:**
- **Durable buffer.** The burst becomes *consumer lag* (a metric), not dropped
  work or user-facing 429s.
- **Partition-per-key = free rate limiting.** Kafka guarantees exactly one
  consumer per partition per group. Map partition *i* → API key *i* and each
  key's pacing needs **zero cross-thread coordination**. This is the killer
  justification: my in-JVM `KeyedWorkerPool` already pinned thread *i* to key
  *i*; Kafka kept the exact invariant and made it durable + multi-machine.
- **Replay as a feature.** New extractor version or new finding category?
  Re-consume the topic, rebuild all findings. Nothing derived is precious.
- **Backpressure via retry topics.** 429 → publish to `retry.60s`, consumer
  moves on. No thread parked for 60 seconds. Dead after N retries → `dlq`.

**Alternatives rejected:**
| Option | Why not |
|---|---|
| RabbitMQ | No log-replay, no partition-ordering-per-key. |
| SQS | FIFO throughput caps, cloud lock-in, no cheap replay. |
| **DB-as-queue** (what v1 had) | `ProcessingJobWorker` polled Postgres every 3s with hand-rolled lock leases (~250 lines). Works, but no partition-per-key invariant, no replay, and polling burns DB IO. Kafka *deleted* this code. |

**The honest counter-number (say it before they do):** for a portfolio app that
processes one corpus at a time, an **in-JVM bounded queue would also work** — I
already had one. Kafka earns its keep only at multi-machine scale or when you
want durable replay. See [`03-honest-tradeoffs.md`](03-honest-tradeoffs.md).

**One-liner:** *"Bursty producer, rate-capped consumers — a textbook durable-buffer
problem. Partition-per-key preserved my per-key rate-limit invariant across
machines, and replay rebuilds every derived table for free."*

---

## 2. Why blob storage (not just keep the file / not a managed store)?

**Problem.** An uploaded archive can be GBs. The web request must return fast,
and the chunker (a different process, possibly a different machine) needs to read
the file later.

**Why a blob store:**
- **Decouples upload from processing.** API streams to blob and returns; the
  Kafka message carries only the `file_url`. The broker never touches file bytes.
- **Cross-process hand-off.** The chunker streams the file back *window by
  window* — never whole-in-memory.
- **Same S3 API everywhere.** MinIO locally, Backblaze B2 in prod — identical
  `PutObject`/`GetObject` calls, so there's no divergent "local disk" code path.

**Why B2 and not S3/GCS:** files are **temporary staging**, deleted after
chunking succeeds (the Postgres chunks are the durable copy). Durability
requirements are *deliberately low* — if staging dies mid-processing, the user
re-uploads and nothing analyzed is lost. So the design picks the cheapest
S3-compatible store: **B2's egress is free to its CDN partners and ~1/4 of S3's
storage price**, and MinIO is free/self-hosted. Paying for S3-grade eleven-nines
durability on files you delete in minutes would be waste.

**Alternative rejected:** keep the file on local disk (v1 did — `./uploads`).
Breaks the moment the API and chunker are separate processes/containers, and
Render's free tier has **ephemeral disk** (wiped on redeploy). Blob storage is
what makes the async, multi-process pipeline possible at all.

**The honest counter-number:** for *small* log files (a few MB), you could skip
the blob entirely and stream the upload straight into the chunker. The blob hop
earns its place only for large files + async decoupling. See doc 03.

**One-liner:** *"Staging, not archival. Cheapest S3-compatible store because I
delete the file after chunking — the durable copy is the chunk rows in Postgres."*

---

## 3. Why a table *per session* for chunks/embeddings?

**Problem.** A session is corpus-scale (10⁵–10⁶ chunks). Semantic drill-down
needs an ANN (HNSW) index. But an HNSW index over *all* sessions' vectors, then
filtered by `WHERE session_id = ?`, hits the **filtered-ANN recall problem**: the
graph is walked *before* the filter applies, so at high tenant selectivity the
walk surfaces mostly *other* sessions' neighbors and recall starves.

**Why table-per-session (`log_chunks_s_{id}`):**
- **Zero filtered-recall loss.** The index contains *only* that corpus's vectors
  — no filter needed, no wasted graph walk.
- **O(session-size) latency**, independent of total system size.
- **`DROP TABLE` lifecycle.** Deleting a corpus is instant — no
  `DELETE ... WHERE` + autovacuum debt on a giant shared table.
- **Right-sized index.** Small sessions (<10k chunks) correctly degrade to an
  exact scan (the planner ignores HNSW) — exact and single-digit ms.

**State the ceiling *proactively* (this is the mark of maturity):** Postgres
degrades on catalog/autovacuum overhead past **tens of thousands of tables**.
Sessions are heavyweight (few per user), so the count stays in the low thousands.
**Exit strategy:** if the product ever pivots to many tiny sessions, consolidate
into `PARTITION BY HASH(user_id)` — same isolation, constant table count, and all
chunk SQL is *already* table-scoped so only name resolution changes.

**Guardrails (they'll ask about SQL injection):** table names are generated
*only* from validated UUIDs, never raw input; the tables live outside JPA in a
small native-SQL repo; one DDL template runs at session creation.

**One-liner:** *"Selectivity — not table size — kills filtered vector search.
Physical per-session isolation gives a right-sized HNSW with zero filtered-recall
loss and DROP-TABLE deletes. Ceiling is catalog overhead at tens of thousands of
tables; sessions are heavyweight so I stay well under it, and the exit is hash
partitioning."*

---

## 4. Why two-layer extraction (parsers + LLM), not "just ask the LLM"?

**Problem.** "How many SQL timeouts between 14:00 and 14:10?" An LLM asked to
count across chunks **miscounts** — it can't see all chunks at once and isn't
deterministic.

**The rule:** *LLMs never count, sum, or average.*
- **Layer 1 — parsers** (regex/Java) run on **every** window → exact
  `log_metrics`. Free, fast, deterministic.
- **Layer 2 — LLM** runs on **anomalous windows only** → `log_findings`
  (explain, classify, fingerprint). Expensive, so spent only where it adds
  meaning.

**The number that justifies the filter:** the `AnomalyDetector` gate (parser
intrinsic-problem OR ≥5 WARN lines OR latency > 3× corpus-p95) means a corpus of
10,000 windows might send only a few hundred to the LLM. **1000s of windows ≠
1000s of LLM calls** — that ratio *is* the cost model.

**One-liner:** *"Numbers from parsers (exact), meaning from the LLM (grounded),
and the LLM only touches anomalous windows. I use LLMs only where deterministic
code fails."*

---

## 5. Why round-robin across N Gemini keys?

**Problem.** Gemini free embeddings = **30K tokens/minute per key**. A chunk ≈
1,500 tokens, so one key drains fast under a burst.

**Why round-robin (not parallel-fan-out):**
- Keys are from **different Google accounts → independent quotas.** N keys = N ×
  30K TPM effective (5 keys = 150K TPM).
- Round-robin (advance the cursor per call) **spreads load evenly** without two
  workers grabbing the same chunk or racing on writes.
- On 429: rotate to the next key and retry **immediately** (fresh quota, no
  sleep). Only sleep once *every* key is exhausted in the same minute.
- **Generic:** add another comma-separated key to `GEMINI_API_KEYS` and
  throughput scales automatically — the pool size is read at runtime.

**Say the honest caveat:** multi-account key pooling is a **rate-limit trick, not
a production capacity strategy** — a real deployment buys a paid tier. It's a
smart *portfolio* solution to a *free-tier* constraint, and I'd frame it exactly
that way.

**One-liner:** *"Five independent free-tier quotas, round-robined by a runtime
cursor — 5× throughput at $0. On 429 I rotate to a fresh key instead of sleeping.
In production you'd just pay for one key."*

---

## 6. Why Postgres for *both* relational and vector (not a dedicated vector DB)?

**Problem.** Need ANN search *and* relational joins (findings → incidents →
reports) *and* transactional consistency between a chunk and its metadata.

**Why pgvector, not Pinecone:**
- **One system of record.** Everything derived is rebuildable from Postgres; a
  second store adds a **sync problem** (vectors and metadata drifting apart) and
  solves nothing here.
- **Transactional consistency** between the chunk row and its embedding — a
  vector DB can't join to your `log_findings`.
- **`ORDER BY embedding <=> :q LIMIT k`** — pgvector makes plain SQL the top-k
  API, over an HNSW index.

**One-liner:** *"A dedicated vector DB buys me nothing and costs me a sync
problem. pgvector gives isolated, right-sized ANN with transactional consistency
to the metadata it must join against."*

---

## 7. The "what I deliberately did NOT use" list (memorize — removal is a strong signal)

| Cut | Why it's not needed |
|---|---|
| **Elasticsearch** | Had it in an early draft; cut it when the query surface stopped depending on open-ended retrieval quality. Evidence lookup is by ID; lexical search is session-scoped (Postgres GIN); semantic is per-session pgvector. One fewer stateful cluster. |
| **Managed/durable object store (S3, GCS)** | Files are temporary staging — low durability is a *feature*, so I use the cheapest S3-compatible store. |
| **Dedicated vector DB** | See §6 — sync problem for no gain. |
| **Kafka exactly-once transactions** | At-least-once + idempotent handlers (fingerprint dedup, natural-key upserts) = equivalent correctness for these write shapes, far simpler. |
| **Async query API / status topic** | Queries are SQL (ms, synchronous). Only *analysis* is long-running, and its progress is the session's status row streamed over SSE. |
| **Microservices** | Two deployables total (Java monolith + Python sidecar), and only because the Python AI ecosystem forces the second. |

**The meta-point to say out loud:** *"Being able to name what I removed, and
why, is a stronger signal than anything I added."*

---

## 8. Ingest at scale: split-manifest parts (design in [docs/04](../04-partitioned-ingest-spec.md); state its implementation status honestly)

The original ingest read the whole file into heap as one Kafka record — I found
that ceiling myself and redesigned it. The redesign: a **splitter** makes one
streaming pass over the blob object and emits **window-aligned byte ranges**;
Kafka carries K tiny `{fileUrl, byteStart, byteEnd}` pointers; parallel consumers
do S3 **ranged GETs** for their slice. Memory is O(one part) forever; a crash
resumes at part granularity; parallelism = partition count.

| Attack | Answer |
|---|---|
| "Why a Kafka event? The URL is already in the documents table." | The event is the **wake-up, not the data**. The alternative is polling the table — which is exactly v1's deleted `ProcessingJobWorker`: 3s poll latency, lease columns, stale-lock cleanup. The broker pushes instantly, delivers to exactly one consumer, and offsets survive crashes. I've built both; I deleted the polling one for cause. |
| "Why pointers, not the log content, through Kafka?" | **Claim-check pattern.** Brokers are engineered for torrents of small records (~1MB default cap), not GB payloads: content-in-topic doubles storage (broker + blob), fights retention against processing time, and can't be ranged-read. Kafka moves facts, blob moves bytes, Postgres holds truth. |
| "Why virtual parts (byte ranges), not physical part files?" | Identical parallelism and resume, **minus one full copy of write I/O** — split points are indexed, not copied. Same idea as Hadoop/Spark input splits. Physical parts' only advantage is console debuggability; a log line covers that. |
| "What if a split cuts a stack trace in half?" | It can't — cuts land only on **window boundaries at record starts** (the splitter reuses the chunker's timestamp regexes). Every part is a set of complete windows, so parts need zero coordination. |
| "Your latency-anomaly rule needs corpus-wide p95 — parts can't know that." | Correct, and it's designed for: local rules (exceptions, WARN bursts) flag during part processing; the p95 rule runs once in a **counter-triggered finalizer** as a single SQL `percentile_cont`. Two-phase statistics — the same reason MapReduce has a reduce step. |
| "At-least-once redelivery of a part would double-insert chunks and double-count metrics." | A `(document_id, part_idx)` **marker row inserted in the same transaction** as the part's writes: redelivery hits the PK conflict and skips everything. Exactly-once *effect* for the price of one insert. |

Continue to [`03-honest-tradeoffs.md`](03-honest-tradeoffs.md) — where I turn
this critical lens on my *own* choices.
