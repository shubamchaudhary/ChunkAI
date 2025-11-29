# Multi-Provider LLM Router - Implementation Guide

## Overview

The multi-provider LLM router distributes requests across multiple free-tier LLM providers using weighted round-robin routing. This solves rate limiting issues by spreading load across providers.

**Total Combined Capacity: 100 RPM (6,000 requests/hour) when all providers are configured**

## Architecture

### Components

1. **LlmProvider Enum** - Defines all supported providers with their rate limits
2. **ProviderClient Interface** - Standard interface for all provider implementations
3. **Provider Clients** - Individual implementations for each provider:
   - GroqProviderClient (30 RPM)
   - GeminiProviderClient (10 RPM)
   - CohereProviderClient (20 RPM)
   - CerebrasProviderClient (30 RPM)
   - SambanovaProviderClient (10 RPM)
4. **WeightedLlmRouter** - Core routing service with round-robin logic
5. **UnifiedLlmService** - Simple facade service
6. **LlmStatsController** - REST API for monitoring and testing

### How It Works

1. **Weighted Slot Distribution**: Each provider gets slots proportional to its RPM
   - Groq (30 RPM) = 30 slots
   - Cerebras (30 RPM) = 30 slots
   - Cohere (20 RPM) = 20 slots
   - etc.

2. **Round-Robin Selection**: Router cycles through weighted slots, selecting next available provider

3. **Automatic Failover**: If a provider fails, router automatically tries next provider

4. **Rate Limit Management**: Each provider tracks its own rate limits per minute

## Setup

### 1. Get API Keys (All Free, No Credit Card)

| Provider | URL | RPM | Daily Limit |
|----------|-----|-----|-------------|
| Groq | https://console.groq.com | 30 | 14,400 |
| Cerebras | https://cerebras.ai | 30 | 10,000 |
| Cohere | https://dashboard.cohere.com | 20 | 1,000/month |
| Gemini | https://aistudio.google.com | 10 | 1,500 |
| SambaNova | https://sambanova.ai | 10 | 1,000 |

### 2. Configure API Keys

#### Option A: Environment Variables (Recommended)

```bash
export LLM_GROQ_API_KEY=gsk_xxxxx
export LLM_CEREBRAS_API_KEY=xxxxx
export LLM_COHERE_API_KEY=xxxxx
export LLM_GEMINI_API_KEY=AIzaSyxxxxx  # or uses existing gemini.api-key
export LLM_SAMBANOVA_API_KEY=xxxxx
```

#### Option B: application.properties

```properties
llm.groq.api-key=gsk_xxxxx
llm.cerebras.api-key=xxxxx
llm.cohere.api-key=xxxxx
llm.gemini.api-key=AIzaSyxxxxx
llm.sambanova.api-key=xxxxx
```

**Note**: If `llm.gemini.api-key` is not set, the router will use the existing `gemini.api-key` from your configuration.

### 3. Minimum Setup (60 RPM)

Only need 2 providers:

```bash
export LLM_GROQ_API_KEY=gsk_xxxxx
export LLM_CEREBRAS_API_KEY=xxxxx
```

This gives you **60 RPM** immediately!

### 4. Full Setup (100 RPM)

Configure all 5 providers for maximum capacity.

## Usage

### Using UnifiedLlmService

```java
@Service
@RequiredArgsConstructor
public class MyService {
    private final UnifiedLlmService llmService;
    
    public void generateContent() {
        // Simple usage - router automatically selects best available provider
        String response = llmService.generateContent("Your prompt here");
        
        // With model override (optional)
        String response2 = llmService.generateContent("Your prompt", "llama-3.3-70b-versatile");
        
        // JSON mode
        String json = llmService.generateJsonContent("Generate JSON...");
    }
}
```

### Migration Strategy

**Phase 1: Parallel Operation**
- Router works alongside existing GeminiClient
- New features use UnifiedLlmService
- Existing code continues using GeminiClient

**Phase 2: Gradual Migration**
- Replace GeminiClient calls with UnifiedLlmService where appropriate
- Router automatically handles rate limiting

**Phase 3: Full Migration** (Optional)
- Replace all GeminiClient calls
- Router becomes primary LLM interface

### Current Limitations

The router currently supports simple prompts. For advanced features like:
- System instructions
- Google Search grounding
- Structured output formats

Continue using `GeminiClient` directly until router is extended.

## Monitoring

### Check Router Statistics

```bash
curl http://localhost:8080/api/v1/llm/stats
```

Response:
```json
{
  "totalProviders": 3,
  "totalRpm": 70,
  "providers": {
    "Groq": {
      "rpm": 30,
      "requestsThisMinute": 5,
      "healthy": true
    },
    "Cerebras": {
      "rpm": 30,
      "requestsThisMinute": 3,
      "healthy": true
    },
    "Gemini": {
      "rpm": 10,
      "requestsThisMinute": 0,
      "healthy": true
    }
  }
}
```

### Health Check

```bash
curl http://localhost:8080/api/v1/llm/health
```

### Test Generation

```bash
curl -X POST http://localhost:8080/api/v1/llm/test \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello, test!"}'
```

## Configuration

### application.properties

```properties
# Router configuration
llm.router.max-retries=5
llm.router.cooldown-ms=120000
llm.router.retry-delay-ms=2000

# Provider API keys and RPM limits
llm.groq.api-key=${LLM_GROQ_API_KEY:}
llm.groq.rpm=30

llm.cerebras.api-key=${LLM_CEREBRAS_API_KEY:}
llm.cerebras.rpm=30

# ... etc for other providers
```

## Benefits

1. **No Rate Limits**: Distributes load across providers
2. **High Availability**: Automatic failover if provider fails
3. **Cost-Free**: All providers offer free tiers
4. **Scalable**: Easy to add more providers
5. **Transparent**: Statistics API shows provider health

## Troubleshooting

### "No LLM providers configured"

**Solution**: Set at least one API key:
```bash
export LLM_GROQ_API_KEY=your_key_here
```

### Provider keeps failing

**Solution**: Check API key is valid, check provider status page

### Still hitting rate limits

**Solution**: 
1. Add more providers (each adds RPM)
2. Reduce RPM settings in config
3. Check router stats to see which provider is overwhelmed

## Next Steps

1. **Extend ProviderClient** to support system instructions
2. **Add embedding support** to router
3. **Create provider health dashboard**
4. **Add cost tracking** (all free, but good for monitoring)

## Files Created

- `examprep-llm/src/main/java/com/examprep/llm/provider/LlmProvider.java`
- `examprep-llm/src/main/java/com/examprep/llm/provider/ProviderClient.java`
- `examprep-llm/src/main/java/com/examprep/llm/provider/clients/*.java` (5 provider clients)
- `examprep-llm/src/main/java/com/examprep/llm/router/WeightedLlmRouter.java`
- `examprep-llm/src/main/java/com/examprep/llm/service/UnifiedLlmService.java`
- `examprep-api/src/main/java/com/examprep/api/controller/LlmStatsController.java`

All files are ready to use! The router will automatically start on application boot.

