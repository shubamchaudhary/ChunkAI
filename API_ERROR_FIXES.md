# API Error Fixes - Summary

## Issues Identified

### 1. **Groq - 413 Payload Too Large**
- **Error**: Request exceeded Groq free tier token limit (12,000 tokens/minute)
- **Root Cause**: Metadata generation was sending 51,028 character prompts (~17,240 tokens)
- **Impact**: Groq rejected requests immediately, router had to failover to other providers

### 2. **SambaNova - 410 Gone**
- **Error**: 410 Gone status code
- **Root Cause**: Endpoint may be deprecated or service unavailable
- **Impact**: Wasted time trying SambaNova before failing over to Gemini

## Fixes Applied

### 1. **Reduced Metadata Prompt Size** (`MetadataGenerator.java`)

**Changed:**
- Document text truncation: **50,000 chars → 25,000 chars**
- Reason: Stay well under provider token limits
- Estimated tokens: ~6,250 tokens (vs ~12,757 tokens before)
- With prompt template: ~8,000 tokens total (safe for all providers)

**Benefits:**
- Stays within Groq's 12,000 token/min limit
- Compatible with all providers
- Still provides enough context for good metadata extraction

### 2. **Enhanced Error Handling** (Provider Clients)

**GroqProviderClient:**
- Added explicit logging for 413 (Payload Too Large) errors
- Marks as non-retryable (skips to next provider immediately)

**SambanovaProviderClient:**
- Added explicit logging for 410 (Gone) errors
- Marks as non-retryable (skips to next provider immediately)

### 3. **Improved Router Logging** (`WeightedLlmRouter.java`)

**Added:**
- Failure reason detection (PayloadTooLarge, ServiceUnavailable, RateLimited, etc.)
- Better logging showing why providers are skipped
- Faster failover when errors are non-retryable

**Benefits:**
- Clearer logs showing what went wrong
- Faster failover (no wasted retries on non-retryable errors)
- Better debugging information

## Expected Behavior Now

### Metadata Generation Flow:

1. **Groq** (if selected first):
   - Should now work with smaller prompts (25K chars)
   - If still too large, fails quickly with 413, skips to next provider

2. **SambaNova** (if selected):
   - If service is unavailable (410), fails quickly, skips to next provider
   - Router continues to other providers

3. **Gemini** (fallback):
   - Works with larger prompts (1M token context window)
   - Successfully handles metadata generation

4. **Cerebras & Cohere**:
   - Will be tried if available
   - Router automatically selects best available provider

### Error Handling Flow:

```
Request → Groq (413 Error) → Skip immediately → SambaNova (410 Error) → Skip immediately → Gemini → Success
```

Instead of:
```
Request → Groq (try 5 times) → SambaNova (try 5 times) → Gemini → Success
```

## Configuration

### Token Limits by Provider (Free Tier):

| Provider | Token Limit | Notes |
|----------|-------------|-------|
| Groq | 12,000/min | Free tier "on_demand" |
| Gemini | 1M input | Very generous context window |
| Cerebras | Varies | Check documentation |
| Cohere | Varies | Check documentation |
| SambaNova | 410 Gone | Service may be deprecated |

### Metadata Generation Settings:

- **Max Document Text**: 25,000 characters (~6,250 tokens)
- **Prompt Template**: ~2,000 characters (~500 tokens)
- **Total Estimated**: ~8,000 tokens (safe for all providers)

## Monitoring

### New Log Messages:

```
[GROQ] Request too large - skipping to next provider | statusCode=413
[SAMBANOVA] Service unavailable (410 Gone) - skipping to next provider | statusCode=410
[ROUTER] Provider request failed | reason=PayloadTooLarge | statusCode=413
[ROUTER] Provider request failed | reason=ServiceUnavailable | statusCode=410
```

### Success Indicators:

- Metadata generation completes successfully
- Router successfully uses available providers
- No wasted retries on non-retryable errors

## Recommendations

1. **Monitor SambaNova**: If 410 errors persist, consider removing SambaNova from router configuration
2. **Adjust Text Size**: If you need more context, can increase from 25K to 30K chars, but monitor Groq errors
3. **Provider Priority**: Router automatically handles provider selection based on availability

## Testing

To verify fixes:

1. Upload documents and check metadata generation logs
2. Verify no 413 errors from Groq (or they fail fast and skip)
3. Verify 410 errors from SambaNova are handled gracefully
4. Check that Gemini successfully completes metadata generation

Expected log sequence:
```
[METADATA] Calling LLM router for metadata extraction | promptLength=~30000
[ROUTER] Starting LLM request | availableProviders=5
[ROUTER] Attempting provider | provider=Groq | ...
[GROQ] Request too large - skipping to next provider | statusCode=413
[ROUTER] Attempting provider | provider=Gemini | ...
[GEMINI] Content generated successfully | ...
[METADATA] Metadata generation completed | ...
```

