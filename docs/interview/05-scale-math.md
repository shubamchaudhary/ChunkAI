# 05 — Scale Math: why this architecture exists

The numbers that justify every heavyweight choice, and the thesis that ties them
together: **design for your target scale, because architecture is the one thing
you can't cheaply retrofit later.**

> Assumptions used throughout (state them — they make your math auditable):
> avg log line ≈ **150 bytes**; an embedding-sized chunk ≈ **60 lines ≈ 1,500
> tokens**; analysis window = **60 seconds**; 7 days = **604,800 seconds**.

---

## 1. The target is an organization, not a demo

```
 50 teams  ×  5 services/team  =  250 services (corpora)
 one session = one service's log archive (e.g. a 7-day window)
```

This is the scale the design is *sold at*. The portfolio demo runs one small
corpus — but you **design for the target, not the demo** (§4 explains why that's
correct, not over-engineering).

---

## 2. One service, 7 days — the volume table

| Tier | lines/sec | 7-day lines | Raw size | **Embedding chunks** (÷60) | **60s windows** |
|---|---|---|---|---|---|
| Low (internal API) | 1 | 6.0×10⁵ | ~90 MB | **~10⁴** | 10,080 |
| **Medium** | 10 | 6.0×10⁶ | ~900 MB | **~10⁵** | 10,080 |
| **High** | 100 | 6.0×10⁷ | ~9 GB | **~10⁶** | 10,080 |
| Very high | 1,000 | 6.0×10⁸ | ~90 GB | ~10⁷ | 10,080 |

**Takeaway:** a single **medium-to-high-traffic service's week = 10⁵–10⁶ chunks
and 0.9–9 GB.** That's the design's sweet spot, and it's an ordinary production
service — not a stretch.

---

## 3. The two-bursts insight (the thing most people miss)

The two axes scale *differently*, and they drive two independent bursts:

| Burst | Scales with | Magnitude (per corpus) | Bottleneck |
|---|---|---|---|
| **Embedding** | log **volume** (chunks) | **10⁵–10⁶** vectors | Gemini TPM (the real monster) |
| **LLM enrichment** | **time** (anomalous windows) | ~15% × 10,080 ≈ **~1,500** calls | Groq RPM |

Why enrichment stays bounded while embeddings explode: **windows are
time-bounded** (~10,080 for any 7-day corpus, regardless of traffic), and only
anomalous windows are enriched. Embeddings, by contrast, must cover **every**
chunk, so they scale with raw volume. This is why the anomaly gate (doc 02 §4)
matters so much — it keeps the *expensive* LLM reasoning bounded even when volume
is huge.

---

## 4. The thesis: design for your target, because architecture doesn't retrofit

This is the sentence that turns "why so heavy?" into a strength:

> **"You design at your target scale, not your demo scale, because a design is
> only as scalable as its architecture — and architecture is the one thing you
> can't cheaply change later. I can swap an algorithm in an afternoon; I cannot
> bolt durability, streaming ingestion, or tenant isolation onto a single-node,
> load-everything-in-memory design without a rewrite. So I commit to the
> architecture my target demands on day one, and keep the *implementations*
> simple."**

**The critical refinement (say this so you're not caught by YAGNI):** this is
*not* "always design for infinite scale." That's over-engineering. It's "design
for my **actual stated target** — 250 services, concurrent multi-hour jobs — and
no further." Two guard-rails prove the discipline:

- I **cut** everything the target *doesn't* need: Elasticsearch, managed durable
  object storage, a dedicated vector DB, exactly-once transactions, microservices
  (doc 02 §7). Designing for target ≠ hoarding technology.
- My **exit strategies are pre-stated** (e.g. per-session tables → hash
  partitioning), so I'm not locked in beyond the target either.

**The cost-of-retrofit argument, made concrete:**

| Concern | Cheap to change later? | Therefore... |
|---|---|---|
| Which regex a parser uses | Yes — an afternoon | Keep it simple now |
| Batch size, window seconds, key count | Yes — config | Tune later |
| Sync in-memory queue → durable multi-machine buffer | **No — touches every worker, delivery semantic, and failure path** | **Commit to Kafka's model up front** |
| Load-file-in-memory → streaming from a blob | **No — changes the whole ingestion contract** | **Commit to blob + streaming up front** |
| Shared table → physically isolated per-tenant ANN | **No — data migration + query rewrite** | **Commit to isolation up front** |

The heavyweight choices are exactly the ones that are *expensive to retrofit*.
That's not a coincidence — it's the whole basis for choosing them early.

---

## 5. At the target, each heavyweight choice becomes load-bearing

The verdict from doc 03 **flips** once you plug in target-scale numbers:

| Choice | At demo scale | **At org target (250 services, multi-hour jobs)** |
|---|---|---|
| **Kafka** | Over-built — in-JVM queue suffices | **Load-bearing.** A 17-hour embedding job (§6) must survive restarts (durability); 250 concurrent bursts need multi-machine per-key pacing (partition-per-key); extractor upgrades need replay across 250 archives. An in-memory queue delivers none of these. |
| **Blob + streaming ingest** | Skippable for MB-sized files | **Load-bearing.** 0.9–9 GB per corpus cannot be buffered in JVM heap — streaming from a blob is the only option. |
| **Per-session tables** | Ahead of need | **Load-bearing.** 10⁵–10⁶ vectors per corpus need a right-sized HNSW; filtered-ANN recall on a shared 250-corpus index would starve (doc 02 §3). |
| **Two-layer + anomaly gate** | Right at any scale | **Load-bearing.** Keeps LLM cost at ~1,500 calls even when volume is 10⁶ chunks. |

---

## 6. The one honest limit to state proactively

Free-tier embeddings top out **below** the high end. Gemini free = 30K TPM/key ≈
20 chunks/min/key; 5 round-robined keys ≈ 6,000 chunks/hr:

| Corpus | Chunks | Free-tier embed time |
|---|---|---|
| Medium service | 10⁵ | **~17 hours** |
| High service | 10⁶ | **~167 hours** (infeasible) |

**Say this before they find it:** *"The round-robin free-key trick scales the
demo to ~10⁵ chunks. Past that, embeddings — not Kafka — are the bottleneck, and
production simply buys a paid embedding tier or runs a local model like
`nomic-embed`. The architecture doesn't change; only the embedding backend does.
That's the point of designing the architecture for the target: the expensive
part (the pipeline shape) already fits, and scaling is a backend swap, not a
redesign."*

---

## 7. The 30-second version to say out loud

> "Our target is org-scale: 50 teams, 5 services each, 250 corpora. One
> medium-to-high service's week is 10⁵–10⁶ chunks and up to 9 GB. At that scale
> the heavyweight pieces are load-bearing: multi-hour jobs need Kafka's
> durability, gigabyte corpora need streaming from a blob, and million-vector
> indexes need per-session isolation. I designed at the target because
> architecture is the one thing you can't cheaply retrofit — I can swap an
> embedding model in an afternoon, but I can't bolt durability onto an in-memory
> queue without a rewrite. And I designed *only* to the target, not past it —
> that's why I cut Elasticsearch, S3, and a dedicated vector DB. The one honest
> limit is free-tier embedding throughput, which a paid key or local model
> removes without touching the architecture."
