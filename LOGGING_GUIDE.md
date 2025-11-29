# Comprehensive Logging Guide

This document explains the structured logging system implemented throughout DeepDocAI v2.0, designed to provide detailed insights into application behavior and performance.

## Logging Format

All logs use a structured format with tags and key-value pairs:

```
[TAG] Message | key1=value1 | key2=value2 | ...
```

### Component Tags

- **[ROUTER]** - Multi-provider LLM router operations
- **[GROQ]** - Groq provider client
- **[GEMINI]** - Gemini provider client
- **[COHERE]** - Cohere provider client
- **[CEREBRAS]** - Cerebras provider client
- **[SAMBANOVA]** - SambaNova provider client
- **[QUERY_ORCH]** - Query orchestrator (main query pipeline)
- **[RETRIEVAL]** - Multi-level retrieval engine
- **[CONTEXT_ASSEMBLER]** - Context assembly from chunks
- **[ANSWER_GEN]** - Answer generation service
- **[METADATA]** - Document metadata generation
- **[DOC_PROCESS]** - Document processing pipeline
- **[CACHE]** - Query cache operations

## Log Levels

- **INFO**: Important operations, milestones, successful completions
- **DEBUG**: Detailed information for troubleshooting
- **WARN**: Issues that don't prevent operation
- **ERROR**: Failures and exceptions

## Key Metrics Logged

### Query Processing

**Query Orchestrator** logs include:
- `queryId` - Unique identifier for the query
- `chatId` - Chat session ID
- `questionLength` - Length of the user question
- `totalDurationMs` - Total time for query processing
- Component durations:
  - `cacheCheckDurationMs` - Cache lookup time
  - `analysisDurationMs` - Query analysis time
  - `retrievalDurationMs` - Document/chunk retrieval time
  - `assemblyDurationMs` - Context assembly time
  - `generationDurationMs` - LLM answer generation time
  - `cacheSaveDurationMs` - Cache save time
- `llmCalls` - Number of LLM calls made
- `sourcesCount` - Number of sources cited

**Example:**
```
[QUERY_ORCH] Query completed successfully | queryId=query-1234567890-1 | totalDurationMs=2345 | cacheCheck=12 | analysis=5 | retrieval=123 | assembly=45 | generation=2100 | cacheSave=8 | llmCalls=1 | sourcesCount=5
```

### LLM Router

**Router** logs include:
- `requestId` - Unique request identifier
- `provider` - Which LLM provider was selected
- `rpm` - Provider's rate limit (requests per minute)
- `usedThisMinute` - Current usage this minute
- `attempt` - Retry attempt number
- `providerDurationMs` - Time for this provider's call
- `totalDurationMs` - Total time including retries
- `responseLength` - Length of LLM response
- `statusCode` - HTTP status code (on errors)
- `rateLimited` - Whether rate limit was hit

**Example:**
```
[ROUTER] Request succeeded | requestId=req-1234567890-1 | provider=Groq | providerDurationMs=1245 | totalDurationMs=1245 | responseLength=3421 | attempts=1
```

### Document Processing

**Document Processing** logs include:
- `processId` - Unique processing identifier
- `documentId` - Document UUID
- `fileName` - Original file name
- `fileType` - File type (PDF, DOCX, etc.)
- `fileSizeBytes` - File size
- Phase durations:
  - `phase1` (Extract & Chunk)
  - `phase2` (Metadata Generation)
  - `phase3` (Embeddings & Save Chunks)
  - `phase4` (Finalize)
- `chunksCreated` - Number of chunks created
- `totalPages` - Number of pages in document
- `topicsCount` - Number of topics extracted
- `entitiesCount` - Number of entities extracted

**Example:**
```
[DOC_PROCESS] Document processing completed successfully | processId=doc-abc12345-1234567890 | documentId=... | fileName=document.pdf | totalDurationMs=45678 | phase1=1234 | phase2=34567 | phase3=8765 | phase4=112 | chunksCreated=45
```

### Retrieval Engine

**Retrieval** logs include:
- `chatId` - Chat session ID
- `questionLength` - Length of query
- `documentIdsCount` - Number of documents to search
- `maxChunks` - Maximum chunks to retrieve
- Level-specific metrics:
  - Level 1: Document discovery
  - Level 2: Vector search
  - Level 3: Keyword reranking
  - Level 4: Diversity filter
- `chunksRetrieved` - Final number of chunks
- `documentsMatched` - Number of documents found

**Example:**
```
[RETRIEVAL] Multi-level retrieval completed | chatId=... | totalDurationMs=234 | documentsMatched=5 | chunksSearched=150 | finalChunks=30
```

### Cache Operations

**Cache** logs include:
- `chatId` - Chat session ID
- `queryHash` - Hash of normalized query
- `similarity` - Semantic similarity score (for semantic hits)
- `hitCount` - Number of times cached result used
- `totalDurationMs` - Total cache lookup time
- `exactMatchDurationMs` - Exact match check time
- `semanticSearchDurationMs` - Semantic search time

**Example:**
```
[CACHE] Semantic cache hit | chatId=... | similarity=0.95 | hitCount=3 | totalDurationMs=234 | semanticSearchDurationMs=189
```

## Filtering Logs

### By Component

To see only router logs:
```bash
grep "\[ROUTER\]" application.log
```

To see only query processing:
```bash
grep "\[QUERY_ORCH\]" application.log
```

To see only document processing:
```bash
grep "\[DOC_PROCESS\]" application.log
```

### By Log Level

To see only errors:
```bash
grep "ERROR" application.log
```

To see warnings and errors:
```bash
grep -E "(WARN|ERROR)" application.log
```

### By Metric

To find slow queries (>5 seconds):
```bash
grep "\[QUERY_ORCH\].*totalDurationMs=" application.log | awk -F'totalDurationMs=' '{print $2}' | awk -F' ' '{if ($1 > 5000) print}'
```

To find rate limit issues:
```bash
grep "rateLimited=true" application.log
```

To track cache hit rate:
```bash
grep -E "\[CACHE\].*(cache hit|cache miss)" application.log
```

## Performance Monitoring

### Query Performance

Track average query times:
```bash
grep "\[QUERY_ORCH\].*totalDurationMs=" application.log | grep -oP 'totalDurationMs=\K[0-9]+' | awk '{sum+=$1; count++} END {print "Average:", sum/count, "ms"}'
```

### Provider Performance

Compare provider response times:
```bash
grep "\[GROQ\].*durationMs=" application.log | grep -oP 'durationMs=\K[0-9]+' | awk '{sum+=$1; count++} END {print "Groq Average:", sum/count, "ms"}'
```

### Document Processing Throughput

Track document processing speed:
```bash
grep "\[DOC_PROCESS\].*Document processing completed" application.log | grep -oP 'totalDurationMs=\K[0-9]+' | awk '{sum+=$1; count++; if ($1 > max) max=$1} END {print "Average:", sum/count, "ms | Max:", max, "ms"}'
```

## Troubleshooting

### Slow Queries

Look for queries with high `totalDurationMs`:
```bash
grep "\[QUERY_ORCH\]" application.log | grep -E "totalDurationMs=[5-9][0-9]{3,}|totalDurationMs=[1-9][0-9]{4,}"
```

Check which phase is slow:
- High `retrievalDurationMs` → Retrieval engine optimization needed
- High `generationDurationMs` → LLM provider issues or large contexts
- High `assemblyDurationMs` → Context assembly bottleneck

### Provider Failures

Find all provider failures:
```bash
grep "\[ROUTER\].*failed" application.log
```

Find rate limit hits:
```bash
grep "rateLimited=true" application.log
```

### Cache Issues

Check cache hit rate:
```bash
cache_hits=$(grep -c "\[CACHE\].*cache hit" application.log)
cache_misses=$(grep -c "\[CACHE\].*cache miss" application.log)
total=$((cache_hits + cache_misses))
echo "Cache Hit Rate: $((cache_hits * 100 / total))%"
```

### Document Processing Failures

Find failed document processing:
```bash
grep "\[DOC_PROCESS\].*failed" application.log
```

Check which phase failed:
- Phase 1 failures → File reading/extraction issues
- Phase 2 failures → Metadata generation (LLM) issues
- Phase 3 failures → Embedding generation issues
- Phase 4 failures → Database update issues

## Configuration

### Log Level Configuration

In `application.properties`:

```properties
# Application logs
logging.level.com.examprep=INFO

# Component-specific logs (for detailed debugging)
logging.level.com.examprep.core.query=DEBUG
logging.level.com.examprep.llm.router=DEBUG
logging.level.com.examprep.core.service=DEBUG

# Suppress noisy logs
logging.level.org.springframework=WARN
logging.level.org.hibernate=WARN
```

### Log File Output

```properties
# Write logs to file
logging.file.name=logs/application.log
logging.file.max-size=100MB
logging.file.max-history=30
```

### JSON Logging (Optional)

For structured log parsing tools like ELK Stack:

```properties
# Add Logback JSON encoder dependency
# Use LogstashEncoder for JSON output
```

## Best Practices

1. **Use Structured Format**: Always use key-value pairs for metrics
2. **Include Unique IDs**: Add request/process IDs for traceability
3. **Log Durations**: Include timing information for performance monitoring
4. **Log Errors with Context**: Include relevant context in error logs
5. **Use Appropriate Levels**: 
   - INFO for important milestones
   - DEBUG for detailed troubleshooting
   - WARN for recoverable issues
   - ERROR for failures

## Log Analysis Tools

### Real-time Monitoring

```bash
# Watch logs in real-time
tail -f logs/application.log | grep "\[QUERY_ORCH\]"

# Watch for errors
tail -f logs/application.log | grep -E "(ERROR|WARN)"
```

### Log Aggregation

For production, consider:
- **ELK Stack** (Elasticsearch, Logstash, Kibana)
- **Loki + Grafana**
- **CloudWatch Logs** (AWS)
- **Datadog**
- **New Relic**

These tools can parse structured logs and provide dashboards for:
- Request rates
- Error rates
- Response time distributions
- Provider health
- Cache hit rates
- Document processing throughput

