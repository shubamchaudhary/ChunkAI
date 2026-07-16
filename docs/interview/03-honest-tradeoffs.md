# 03 — Honest Tradeoffs: where this is over-engineered

You asked me to be honest, so here it is. **None of this is a flaw to hide — it's
material to volunteer.** An interviewer trusts a candidate who critiques their own
design far more than one who defends every choice as optimal. Lead with: *"For the
actual load this runs at, several pieces are heavier than they need to be — here's
what I'd simplify, and why I built them anyway."*

The honest theme: **this is a system designed for a scale it doesn't yet run at,
built to demonstrate that I can reason about that scale.** That's a legitimate
purpose for a portfolio project — as long as you *say* it.

---

## The over-engineering, ranked by honesty-impact

### 1. Kafka is heavier than the actual *demo* load needs ⭐ (but see the scale caveat)

> **Read this section together with [`05-scale-math.md`](05-scale-math.md).** The
> verdict below is about the **single-corpus demo**. At the **org target** (250
> services, concurrent multi-hour jobs) Kafka flips to *load-bearing* — the
> numbers are in doc 05 §5. The honest position is: *"over-built for the demo I
> run today, correctly-built for the target I designed for."*

**The reality (at demo scale):** the app processes one corpus at a time on
free-tier infra. I *already had* an in-JVM `KeyedWorkerPool` (thread-per-key
bounded queue) that did the per-key pacing correctly. For a single-node
deployment, that was enough.

**What Kafka actually buys me here:** durable replay and a multi-machine story —
neither of which a portfolio demo exercises. It also *cost* me real operational
surface: a broker to run, topics to pre-create (Redpanda Serverless doesn't
auto-create them), consumer-group rebalancing to reason about, and a whole class
of "why is nothing consuming" debugging.

**The honest framing:** *"Kafka is the right answer at scale and a great vehicle
to demonstrate the partition-per-key idea, but for this app's real load an
in-memory bounded queue would have shipped faster with less to operate. I kept it
because the partition-per-key invariant and replay story are the most
interesting things about the design — but I won't pretend the load demands it."*

**When it *would* be justified:** multiple worker machines, or the need to replay
months of ingestion after an extractor change. Both are real at company scale;
neither is real for me yet. **At the org target both become real** — see doc 05
§5 for why a 17-hour embedding job across 250 concurrent corpora makes Kafka's
durability and multi-machine pacing load-bearing, not decorative.

---

### 2. A Python sidecar (second language, second deployable) for the graphs

**The reality:** correlation and drill-down could be a **Java state machine over
a state record** — LangGraph's cycles-with-budgets pattern isn't magic. Adding
Python means a second Docker image, a second Render service, a second cold-start,
and cross-service HTTP where an in-process call would do.

**Why I did it anyway:** the Python AI ecosystem (LangGraph, LangChain) is where
the graph-orchestration tooling lives, and "I can integrate a polyglot service
over HTTP" is itself a demonstrable skill. But it's the single biggest
*operational* complication in the stack for arguably the least *architectural*
necessity.

**The honest framing:** *"The graphs are a state machine — the pattern matters
more than the library. If I were optimizing for operational simplicity I'd
implement both graphs in Java and drop a whole deployable. I chose the Python
sidecar to use the mature graph tooling, and I'd revisit that under real ops
pressure."*

---

### 3. Table-per-session is elegant but ahead of its need

**The reality:** at portfolio scale I have a handful of sessions. A single shared
`log_chunks` table with a `session_id` column and a composite index would work
*fine* today and is simpler (no runtime DDL, no name-generation guardrails, no
JPA-bypass repo).

**Why the per-session design is still defensible:** it's the *correct* answer for
the 10⁵–10⁶-chunk target, and the filtered-ANN recall argument is real — but I
should be honest that at *current* volume it's solving a problem I don't have
yet. The runtime `CREATE TABLE` also adds a small injection-surface I had to
guard (UUID-only names).

**The honest framing:** *"Per-session tables are right for the scale I designed
for and wrong-ly complex for the scale I run at. The exit — hash partitioning —
is mechanical, and honestly a shared partitioned table would've been the pragmatic
v1."*

---

### 4. Multi-account key round-robin is a clever hack, not a strategy

**The reality:** pooling free-tier keys from different Google accounts to
multiply quota is a **rate-limit workaround**. It's fragile (accounts, ToS,
per-project daily caps) and a real system just pays for one key with a real
quota.

**Why it's still worth showing:** it demonstrates I understand rate limits, TPM
vs RPM vs RPD, and can design a generic, runtime-scalable rotation. Frame it as
*"a smart solution to a self-imposed $0 constraint,"* not as production capacity
planning.

---

### 5. Smaller ones, briefly

| Thing | Honest read |
|---|---|
| **Blob store for tiny files** | For a few-MB log file you could stream the upload straight into the chunker and skip the blob + its Kafka reference entirely. The blob only earns its place for large files + true async decoupling. |
| **SSE for progress** | Fine and lightweight — but for a single-user demo, polling the status row every 2s would've been simpler and indistinguishable to the user. Not a real over-reach, just not necessary. |
| **Groundedness self-correction loops (max-2 regenerate)** | Genuinely valuable for anti-hallucination, but adds latency and token cost. At portfolio scale a single grounded generation with a citation check might be enough. |
| **Two embedding providers (Groq + Gemini) + provider flags** | Flexibility I added while chasing rate limits. It's config surface that a single committed provider wouldn't need. |

---

## What is *genuinely* right-sized (don't over-apologize)

Balance matters — some choices are correct at *any* scale, and you should defend
these without hedging:

- **Two-layer extraction (parsers for numbers, LLM for meaning).** Right at every
  scale. Using an LLM to count is simply wrong; this is the design's best idea.
- **Postgres as the single system of record + pgvector.** Avoiding a second
  vector store is correct even at small scale — a sync problem for no gain.
- **Ingest-time enrichment + materialized findings.** The core thesis. It's what
  makes whole-corpus questions answerable at all; nothing simpler solves that.
- **Grounding chain (report → incidents → findings → chunk IDs → lines).**
  Anti-hallucination by construction. Correct at any scale.
- **Anomaly gate before the LLM.** Bounds cost; nothing simpler achieves it.

---

## The single best sentence to say

> *"Architectures are stage-appropriate, not absolute. v1 was a monolith with a
> DB job queue — right for its stage. This design is built for the log-analytics
> workload at 10⁵–10⁶ chunks, and a few pieces — Kafka, the Python sidecar,
> per-session tables — are ahead of the load I actually run. I built them to
> demonstrate I can reason at that scale, and I can tell you exactly which ones
> I'd cut to ship the pragmatic version tomorrow: in-JVM queue instead of Kafka,
> Java state machine instead of the Python sidecar, one partitioned chunk table
> instead of per-session tables."*

If you can say that paragraph, you've turned every "over-engineering" question
into a demonstration of judgment.
