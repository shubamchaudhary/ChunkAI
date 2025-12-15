# ChunkAI Architecture Documentation

## Complete End-to-End System Design Guide

This document provides a comprehensive understanding of the ChunkAI application architecture, including the enhanced API key management, batch embedding system, and scalable document processing.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Token Bucket Rate Limiter](#token-bucket-rate-limiter)
4. [API Key Management](#api-key-management)
5. [Batch Embedding System](#batch-embedding-system)
6. [Document Processing Pipeline](#document-processing-pipeline)
7. [Configuration Guide](#configuration-guide)
8. [Performance Optimization](#performance-optimization)
9. [Scalability Considerations](#scalability-considerations)

---

## System Overview

ChunkAI is a document intelligence platform that:
- Processes PDF, PPT, images, and text documents
- Generates semantic embeddings for intelligent search
- Provides RAG (Retrieval Augmented Generation) for Q&A
- Supports bulk uploads (100s of documents)
- Efficiently manages multiple API keys

### Technology Stack

| Component | Technology |
|-----------|------------|
| Backend | Spring Boot 3.2, Java 17 |
| Database | PostgreSQL + pgvector |
| LLM | Google Gemini 2.5 Flash |
| Embeddings | text-embedding-004 (768 dimensions) |
| Frontend | React 19, Vite, Tailwind CSS |
| Auth | JWT (24-hour tokens) |

---

## Architecture Diagram

```
                                    ┌─────────────────────────────────────┐
                                    │         FRONTEND (React)            │
                                    │  ┌─────────┐ ┌─────────┐ ┌────────┐ │
                                    │  │  Login  │ │ Upload  │ │ Query  │ │
                                    │  │ Register│ │ (500+)  │ │Interface│ │
                                    │  └────┬────┘ └────┬────┘ └───┬────┘ │
                                    └───────┼──────────┼───────────┼──────┘
                                            │          │           │
                                            ▼          ▼           ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                              API GATEWAY (Spring Boot)                        │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐  ┌─────────────┐ │
│  │ AuthController │  │DocumentController│ │ QueryController│  │ ChatController│
│  │  - /register   │  │  - /upload     │  │  - /query      │  │  - /chats   │ │
│  │  - /login      │  │  - /upload/bulk│  │  - RAG Search  │  │             │ │
│  │  - /validate   │  │  - /status     │  │                │  │             │ │
│  └───────┬────────┘  └───────┬────────┘  └───────┬────────┘  └─────────────┘ │
└──────────┼───────────────────┼───────────────────┼────────────────────────────┘
           │                   │                   │
           ▼                   ▼                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           CORE SERVICES                                       │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                   DocumentProcessingService                          │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                  │    │
│  │  │   Extract   │→ │   Chunk     │→ │   Embed     │→ [Save to DB]   │    │
│  │  │   (PDF,PPT) │  │ (512 tokens)│  │(BATCH API!) │                  │    │
│  │  └─────────────┘  └─────────────┘  └──────┬──────┘                  │    │
│  └───────────────────────────────────────────┼──────────────────────────┘    │
│                                              │                               │
│  ┌───────────────────────────────────────────▼──────────────────────────┐   │
│  │                    EmbeddingService (BATCH MODE)                     │   │
│  │  ┌─────────────────────────────────────────────────────────────────┐ │   │
│  │  │             BatchEmbeddingService (20 texts/API call)           │ │   │
│  │  │  - Collects chunks into batches of 20                           │ │   │
│  │  │  - Parallel processing across API keys                          │ │   │
│  │  │  - 20x throughput improvement!                                  │ │   │
│  │  └─────────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                              │                               │
│  ┌───────────────────────────────────────────▼──────────────────────────┐   │
│  │              RateLimitedApiKeyManager (TOKEN BUCKET)                 │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                   │   │
│  │  │   Key #1    │  │   Key #2    │  │   Key #3    │  (Auto-scales!)   │   │
│  │  │ 15 req/min  │  │ 15 req/min  │  │ 15 req/min  │                   │   │
│  │  │ Token Bucket│  │ Token Bucket│  │ Token Bucket│                   │   │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘                   │   │
│  │         └────────────────┼────────────────┘                          │   │
│  │                   INTELLIGENT SELECTION                              │   │
│  │              (Pick healthiest key with most tokens)                  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────┬────────────────────────────────────┘
                                          │
                                          ▼
                           ┌──────────────────────────────┐
                           │    Google Gemini API         │
                           │  ┌────────────────────────┐  │
                           │  │   batchEmbedContents   │  │
                           │  │   (20 texts per call)  │  │
                           │  └────────────────────────┘  │
                           │  ┌────────────────────────┐  │
                           │  │    generateContent     │  │
                           │  │    (RAG Responses)     │  │
                           │  └────────────────────────┘  │
                           └──────────────────────────────┘
                                          │
                                          ▼
                           ┌──────────────────────────────┐
                           │   PostgreSQL + pgvector      │
                           │  ┌────────────────────────┐  │
                           │  │    document_chunks     │  │
                           │  │  (768-dim embeddings)  │  │
                           │  │   HNSW Index for       │  │
                           │  │   fast similarity      │  │
                           │  └────────────────────────┘  │
                           └──────────────────────────────┘
```

---

## Token Bucket Rate Limiter

### What is a Token Bucket?

The Token Bucket algorithm is like a bucket that fills with tokens at a constant rate. Each API request consumes one token. If the bucket is empty, requests must wait.

```
┌─────────────────────────────────────────────────────────────┐
│                    TOKEN BUCKET CONCEPT                      │
│                                                             │
│   Initial State:        After 5 requests:    After waiting: │
│   ┌───────────┐        ┌───────────┐        ┌───────────┐  │
│   │ ●●●●●●●●● │ 15     │ ●●●●●●●●● │ 10     │ ●●●●●●●●● │ 12│
│   │ ●●●●●●    │ tokens │ ●         │ tokens │ ●●●       │ tokens
│   └───────────┘        └───────────┘        └───────────┘  │
│                                                             │
│   Refill Rate: 0.25 tokens/second (15 per minute)          │
└─────────────────────────────────────────────────────────────┘
```

### Key Parameters

```java
// TokenBucket.java configuration
REQUESTS_PER_MINUTE = 15.0    // Gemini Free Tier limit
BURST_CAPACITY = 15.0          // Can burst up to 15 requests
REFILL_RATE = 0.25 tokens/sec  // 15 per minute = 0.25 per second
```

### How It Works

1. **Initialization**: Bucket starts full (15 tokens)
2. **Request**: Each API call consumes 1 token
3. **Refill**: Tokens regenerate at 0.25/second
4. **Burst**: Can make 15 requests immediately if bucket is full
5. **Wait**: If empty, waits for tokens to refill

### Implementation Details

```java
// Acquiring a token
public boolean tryAcquire() {
    refill();  // Add tokens based on elapsed time
    if (availableTokens >= 1) {
        availableTokens--;
        return true;
    }
    return false;  // No tokens available
}

// Refilling tokens
private void refill() {
    double elapsed = (now - lastRefill) / 1_000_000_000.0;  // seconds
    double tokensToAdd = elapsed * refillRatePerSecond;
    availableTokens = min(capacity, availableTokens + tokensToAdd);
}
```

### Advantages Over Simple Delay

| Approach | Behavior | Use Case |
|----------|----------|----------|
| Simple Delay (100ms) | Constant wait | Consistent but slow |
| Token Bucket | Burst + smooth | Better for real workloads |

---

## API Key Management

### Dynamic Key Distribution

The system automatically distributes load across all configured API keys:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    API KEY SELECTION ALGORITHM                          │
│                                                                         │
│  Step 1: Filter disabled keys (failed 3+ times)                        │
│          ┌───────┐  ┌───────┐  ┌───────┐                               │
│          │ Key 1 │  │ Key 2 │  │ Key 3 │                               │
│          │ OK ✓  │  │ OK ✓  │  │ DEAD ✗│                               │
│          └───┬───┘  └───┬───┘  └───────┘                               │
│              │          │                                               │
│  Step 2: Sort by available tokens (descending)                         │
│          Key 2 (12 tokens) > Key 1 (5 tokens)                          │
│              │                                                          │
│  Step 3: Try to acquire from healthiest key                            │
│          Key 2.tryAcquire() → Success!                                 │
│              │                                                          │
│  Step 4: Return key for API call                                       │
│          return "KEY_2_VALUE"                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Adding More Keys

Simply add comma-separated keys to your configuration:

```properties
# application.properties
gemini.api-keys=KEY1,KEY2,KEY3,KEY4,KEY5
```

Or via environment variable:
```bash
export GEMINI_API_KEYS=KEY1,KEY2,KEY3,KEY4,KEY5
```

The system automatically:
1. Detects new keys every 5 minutes
2. Creates token buckets for new keys
3. Distributes load across all keys
4. Logs key health status every minute

### Key Failure Handling

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    KEY FAILURE HANDLING FLOW                            │
│                                                                         │
│  API Call Failed                                                        │
│       │                                                                 │
│       ▼                                                                 │
│  ┌─────────────────┐                                                   │
│  │ Categorize Error│                                                   │
│  └────────┬────────┘                                                   │
│           │                                                             │
│     ┌─────┴─────┬──────────┬──────────┬───────────┐                   │
│     ▼           ▼          ▼          ▼           ▼                   │
│  ┌──────┐  ┌───────┐  ┌───────┐  ┌────────┐  ┌────────┐              │
│  │ 429  │  │ 403   │  │ 401   │  │ LEAKED │  │ TIMEOUT│              │
│  │ Rate │  │Forbid │  │Unauth │  │  Key   │  │        │              │
│  └──┬───┘  └───┬───┘  └───┬───┘  └───┬────┘  └───┬────┘              │
│     │          │          │          │           │                    │
│     ▼          ▼          ▼          ▼           ▼                    │
│  Deplete    Mark        Mark       Try        Retry                   │
│  Bucket     Failure     Failure    Next       Later                   │
│             (+1)        (+1)       Key                                │
│                                                                        │
│  After 3 consecutive failures: DISABLE KEY for 5 minutes              │
│  Key auto-recovers after cooldown period                              │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Batch Embedding System

### The Problem with Single Embeddings

```
Traditional Approach (1 embedding per API call):
─────────────────────────────────────────────────
Document with 100 chunks:
  Chunk 1 → API → Embedding 1 (1 second)
  Chunk 2 → API → Embedding 2 (1 second)
  ...
  Chunk 100 → API → Embedding 100 (1 second)

  TOTAL: 100 API calls, ~100 seconds

  With rate limit (15 req/min): 100/15 = 6.7 minutes!
```

### The Batch Solution (20x Faster!)

```
Batch Approach (20 embeddings per API call):
─────────────────────────────────────────────────
Document with 100 chunks:
  [Chunk 1-20]   → API → [Embedding 1-20]   (1 call)
  [Chunk 21-40]  → API → [Embedding 21-40]  (1 call)
  [Chunk 41-60]  → API → [Embedding 41-60]  (1 call)
  [Chunk 61-80]  → API → [Embedding 61-80]  (1 call)
  [Chunk 81-100] → API → [Embedding 81-100] (1 call)

  TOTAL: 5 API calls, ~5 seconds

  With 3 keys in parallel: 5/3 = 1.7 seconds!
```

### How Batch Processing Works

```java
// BatchEmbeddingService.java

// 1. Collect texts into batches
List<List<String>> batches = splitIntoBatches(texts, 20);  // 20 per batch

// 2. Build batch request
Map<String, Object> request = Map.of(
    "requests", texts.stream()
        .map(text -> Map.of(
            "model", "models/text-embedding-004",
            "content", Map.of("parts", List.of(Map.of("text", text)))
        ))
        .toList()
);

// 3. Call batch API
POST /models/text-embedding-004:batchEmbedContents
Body: { "requests": [...] }
Response: { "embeddings": [{ "values": [...] }, ...] }

// 4. Process in parallel across keys
CompletableFuture.supplyAsync(() -> processBatch(batch), executor)
```

### Performance Comparison

| Scenario | Single Mode | Batch Mode | Improvement |
|----------|-------------|------------|-------------|
| 100 chunks, 1 key | 6.7 min | 20 sec | **20x** |
| 100 chunks, 3 keys | 2.2 min | 7 sec | **19x** |
| 500 chunks, 3 keys | 11 min | 35 sec | **19x** |

---

## Document Processing Pipeline

### Complete Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DOCUMENT PROCESSING PIPELINE                         │
│                                                                         │
│  1. UPLOAD PHASE                                                       │
│     User → FileUpload Component → DocumentController.uploadBulk()      │
│                                        │                                │
│                                        ▼                                │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │ For each file:                                               │   │
│     │   - Validate (type, size, duplicates)                       │   │
│     │   - Save to disk (./uploads/{documentId})                   │   │
│     │   - Create Document entity (PENDING)                        │   │
│     │   - Create ProcessingJob (QUEUED)                           │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                        │                                │
│  2. PROCESSING PHASE (Background Worker - every 3 seconds)            │
│                                        │                                │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │ ProcessingJobWorker.processQueuedJobs()                     │   │
│     │   - Fetch up to 10 QUEUED jobs                              │   │
│     │   - Lock each job (status=PROCESSING)                       │   │
│     │   - Process in parallel                                     │   │
│     └───────────────────────┬─────────────────────────────────────┘   │
│                             │                                          │
│                             ▼                                          │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │ DocumentProcessingService.processDocument()                 │   │
│     │                                                             │   │
│     │   ┌──────────────┐                                         │   │
│     │   │   EXTRACT    │ PDFProcessor / PPTProcessor / etc.      │   │
│     │   │   Text from  │ Returns: List<PageContent>              │   │
│     │   │   Document   │                                         │   │
│     │   └──────┬───────┘                                         │   │
│     │          │                                                  │   │
│     │          ▼                                                  │   │
│     │   ┌──────────────┐                                         │   │
│     │   │    CHUNK     │ ChunkingService.chunkByPages()          │   │
│     │   │   Split into │ Config: 512 tokens, 50 token overlap    │   │
│     │   │   Segments   │ Returns: List<ChunkingResult>           │   │
│     │   └──────┬───────┘                                         │   │
│     │          │                                                  │   │
│     │          ▼                                                  │   │
│     │   ┌──────────────┐                                         │   │
│     │   │    EMBED     │ EmbeddingService.generateEmbeddingsParallel()
│     │   │   Generate   │ BATCH API: 20 chunks per request        │   │
│     │   │   Vectors    │ PARALLEL: Across all API keys           │   │
│     │   └──────┬───────┘                                         │   │
│     │          │                                                  │   │
│     │          ▼                                                  │   │
│     │   ┌──────────────┐                                         │   │
│     │   │    SAVE      │ DocumentChunkRepository.batchSave()     │   │
│     │   │   To DB      │ Native SQL for pgvector compatibility   │   │
│     │   └──────────────┘                                         │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                        │
│  3. QUERY PHASE                                                        │
│     User Question → QueryController → RagService                       │
│                                        │                                │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │ RAG Pipeline (4 Stages):                                    │   │
│     │   1. Embed query → Vector search → Top 150 chunks           │   │
│     │   2. Parallel prompt generation (3 API calls)               │   │
│     │   3. Combine contexts + history                             │   │
│     │   4. Final LLM response with sources                        │   │
│     └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Configuration Guide

### Environment Variables

```bash
# Required: API Keys (comma-separated for multiple)
export GEMINI_API_KEYS=AIza...,AIza...,AIza...

# Database
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/examprep
export SPRING_DATASOURCE_USERNAME=examprep
export SPRING_DATASOURCE_PASSWORD=examprep

# Security
export JWT_SECRET=your-256-bit-secret-key-here

# CORS (comma-separated origins)
export CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
```

### application.properties

```properties
# Gemini Configuration
gemini.api-keys=${GEMINI_API_KEYS:}
gemini.embedding-model=text-embedding-004
gemini.generation-model=gemini-2.5-flash
gemini.max-output-tokens=8192
gemini.max-context-chunks=150

# Database
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5434/examprep}
spring.datasource.hikari.maximum-pool-size=50
spring.datasource.hikari.minimum-idle=15

# File Upload
spring.servlet.multipart.max-file-size=1GB
spring.servlet.multipart.max-request-size=2GB
file.storage.path=./uploads
```

### Rate Limit Tuning

Modify `RateLimitedApiKeyManager.java`:

```java
// For paid API tier (higher limits)
private static final double REQUESTS_PER_MINUTE = 60.0;
private static final double BURST_CAPACITY = 60.0;

// For aggressive rate limiting
private static final long MAX_WAIT_MS = 60000;  // 1 minute max wait
private static final int MAX_CONSECUTIVE_FAILURES = 5;
```

---

## Performance Optimization

### Throughput Calculations

With 3 free tier API keys:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    THROUGHPUT CALCULATION                               │
│                                                                         │
│  Single Embedding Mode:                                                 │
│    3 keys × 15 req/min = 45 embeddings/min                             │
│                                                                         │
│  Batch Embedding Mode (20/batch):                                       │
│    3 keys × 15 req/min × 20 texts/req = 900 embeddings/min             │
│                                                                         │
│  Improvement: 900/45 = 20x faster!                                      │
│                                                                         │
│  Document Processing Rate:                                              │
│    Avg document = 50 chunks                                             │
│    Batch mode: 50/20 = 3 API calls                                      │
│    With 3 keys: ~1-2 seconds per document                              │
│    Capacity: ~30-40 documents per minute                               │
└─────────────────────────────────────────────────────────────────────────┘
```

### Optimization Tips

1. **Add More API Keys**: Each key adds 15 req/min capacity
2. **Increase Batch Size**: Up to 100 texts per batch (Gemini limit)
3. **Parallel Processing**: Background worker processes 10 jobs in parallel
4. **Connection Pool**: HikariCP configured for 50 connections

---

## Scalability Considerations

### Horizontal Scaling

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    HORIZONTAL SCALING ARCHITECTURE                      │
│                                                                         │
│                    ┌──────────────────┐                                │
│                    │   Load Balancer  │                                │
│                    └────────┬─────────┘                                │
│                             │                                          │
│         ┌───────────────────┼───────────────────┐                     │
│         │                   │                   │                     │
│         ▼                   ▼                   ▼                     │
│    ┌─────────┐        ┌─────────┐        ┌─────────┐                 │
│    │ API     │        │ API     │        │ API     │                 │
│    │ Instance│        │ Instance│        │ Instance│                 │
│    │   #1    │        │   #2    │        │   #3    │                 │
│    └────┬────┘        └────┬────┘        └────┬────┘                 │
│         │                  │                  │                       │
│         └──────────────────┼──────────────────┘                      │
│                            │                                          │
│                 ┌──────────┴──────────┐                              │
│                 │    PostgreSQL       │                              │
│                 │    (Shared DB)      │                              │
│                 └─────────────────────┘                              │
│                                                                       │
│  Notes:                                                              │
│  - Each instance has its own ApiKeyManager                           │
│  - ProcessingJob locking prevents duplicate work                     │
│  - Shared file storage (S3/NFS) needed for uploads                   │
└─────────────────────────────────────────────────────────────────────────┘
```

### Database Optimization

```sql
-- Key indexes for performance
CREATE INDEX idx_chunks_embedding ON document_chunks
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

CREATE INDEX idx_chunks_user_chat ON document_chunks (user_id, chat_id);
CREATE INDEX idx_jobs_queue ON processing_jobs (status, priority, created_at);
```

---

## Summary

ChunkAI is designed for:

1. **High Throughput**: 20x improvement with batch embeddings
2. **Reliability**: Token bucket rate limiting prevents API failures
3. **Scalability**: Dynamic key management, horizontal scaling ready
4. **Efficiency**: Parallel processing, connection pooling, batch saves
5. **User Experience**: Bulk uploads, real-time progress, error handling

### Quick Start

```bash
# 1. Set API keys
export GEMINI_API_KEYS=key1,key2,key3

# 2. Start database
docker-compose up -d postgres

# 3. Run backend
./gradlew :examprep-api:bootRun

# 4. Run frontend
cd examprep-frontend && npm run dev
```

### Adding New API Keys

Just update the environment variable or config:
```bash
# Add a 4th key
export GEMINI_API_KEYS=key1,key2,key3,key4
# System auto-detects and starts using it within 5 minutes
```

---

*Last updated: December 2024*
