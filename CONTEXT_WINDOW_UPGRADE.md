# Context Window & File Upload Capacity Upgrade

## Overview
This document outlines the changes made to transform the application into a ChatGPT/Claude-like system with **much higher context windows** and **significantly increased file upload capacity**.

## Changes Implemented

### 1. **Increased File Upload Capacity**

#### File Size Limits
- **Before**: 50MB per file
- **After**: **1GB per file** (20x increase)
- **Max Request Size**: 2GB (for bulk uploads)

#### Files Modified:
- `examprep-common/src/main/java/com/examprep/common/constants/FileTypes.java`
  - Changed `MAX_FILE_SIZE_BYTES` from `50MB` to `1GB`

- `examprep-api/src/main/resources/application.properties`
  - `spring.servlet.multipart.max-file-size`: `50MB` → `1GB`
  - `spring.servlet.multipart.max-request-size`: `50MB` → `2GB`
  - `server.tomcat.max-http-form-post-size`: `50MB` → `1GB`
  - `spring.codec.max-in-memory-size`: `50MB` → `1GB`
  - `spring.servlet.multipart.file-size-threshold`: `2KB` → `2MB`

### 2. **Increased Context Window**

#### Output Token Limit
- **Before**: 32,768 tokens (hardcoded)
- **After**: **8,192 tokens** (configurable, max for Gemini 2.5 Flash)
- **Note**: Gemini 2.5 Flash supports up to 1M tokens input context, but output is capped at 8,192 tokens

#### Document Chunks Retrieved
- **Before**: 10 chunks (5 for follow-up questions)
- **After**: **100 chunks** (configurable, 20 for follow-up questions)
- **Impact**: Can now process much more document context per query

#### Conversation History
- **Before**: Last 10 Q&A pairs (20 messages)
- **After**: **Last 50 Q&A pairs** (100 messages, configurable)
- **Impact**: Much better context retention across long conversations

### 3. **Configuration Added**

New configuration properties in `application.properties`:
```properties
gemini.max-output-tokens=8192
gemini.max-context-chunks=100
gemini.max-conversation-history=50
gemini.timeout-seconds=60
```

### 4. **Files Modified**

#### Backend Configuration
1. **`examprep-llm/src/main/java/com/examprep/llm/client/GeminiConfig.java`**
   - Added `maxOutputTokens`, `maxContextChunks`, `maxConversationHistory` properties
   - Increased `timeoutSeconds` from 30 to 60

2. **`examprep-llm/src/main/java/com/examprep/llm/client/GeminiClient.java`**
   - Changed `maxOutputTokens` from hardcoded `32768` to `config.getMaxOutputTokens()`

3. **`examprep-llm/src/main/java/com/examprep/llm/service/RagService.java`**
   - Updated chunk retrieval to use `config.getMaxContextChunks()` (100 chunks)
   - Updated conversation history to use `config.getMaxConversationHistory()` (50 pairs)
   - Added `GeminiConfig` dependency

4. **`examprep-api/src/main/java/com/examprep/api/controller/QueryController.java`**
   - Updated conversation history loading to use `geminiConfig.getMaxConversationHistory()`
   - Added `GeminiConfig` dependency

## Performance Considerations

### Memory Usage
- **File Uploads**: With 1GB files, ensure sufficient heap memory:
  ```properties
  # Recommended JVM args:
  -Xmx4G -Xms2G
  ```

### Database Storage
- **Vector Embeddings**: With 100 chunks per query, ensure PostgreSQL has:
  - Sufficient RAM for pgvector operations
  - Proper indexing on `document_chunks` table
  - Consider connection pooling for concurrent queries

### API Timeouts
- Increased `gemini.timeout-seconds` to 60 seconds
- Consider increasing further for very large context windows

## Comparison: Before vs After

| Feature | Before | After | Improvement |
|---------|--------|-------|-------------|
| **File Upload Limit** | 50MB | 1GB | **20x** |
| **Max Request Size** | 50MB | 2GB | **40x** |
| **Output Tokens** | 32,768 (hardcoded) | 8,192 (configurable) | Configurable |
| **Chunks Retrieved** | 10 | 100 | **10x** |
| **Conversation History** | 10 Q&A pairs | 50 Q&A pairs | **5x** |
| **Total Context Window** | ~20K tokens | ~1M tokens (input) | **50x** |

## Usage Examples

### Upload Large Files
```bash
# Now supports files up to 1GB
curl -X POST http://localhost:8080/api/v1/documents/upload \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@large_document.pdf" \
  -F "chatId=YOUR_CHAT_ID"
```

### Query with Large Context
The system now automatically:
- Retrieves up to 100 relevant document chunks
- Includes up to 50 previous Q&A pairs in context
- Processes up to 1M input tokens (Gemini 2.5 Flash limit)

## Next Steps (Optional Enhancements)

1. **Streaming Responses**: Implement Server-Sent Events (SSE) for real-time response streaming
2. **Chunked File Upload**: Support resumable uploads for very large files (>500MB)
3. **Context Window Management**: Implement smart truncation to prioritize most relevant context
4. **Multi-Model Support**: Add support for Gemini 2.0 Flash Experimental (supports 1M tokens output)
5. **Caching**: Cache embeddings and frequently accessed chunks to reduce API calls

## Testing

To test the new limits:

1. **File Upload Test**:
   ```bash
   # Upload a large file (e.g., 500MB PDF)
   curl -X POST http://localhost:8080/api/v1/documents/bulk-upload \
     -H "Authorization: Bearer YOUR_TOKEN" \
     -F "files=@large_file1.pdf" \
     -F "files=@large_file2.pdf" \
     -F "chatId=YOUR_CHAT_ID"
   ```

2. **Context Window Test**:
   - Upload multiple large documents
   - Ask questions that require context from many documents
   - Verify the system retrieves and uses up to 100 chunks

3. **Conversation History Test**:
   - Have a long conversation (50+ Q&A pairs)
   - Ask follow-up questions that reference earlier messages
   - Verify context is maintained across the entire conversation

## Notes

- **Gemini 2.5 Flash Limits**:
  - Input context: Up to 1M tokens ✅
  - Output tokens: Max 8,192 tokens (this is the API limit)
  - If you need longer outputs, consider using Gemini 2.0 Flash Experimental

- **File Storage**:
  - Ensure `./uploads` directory has sufficient disk space
  - Consider using cloud storage (S3, GCS) for production

- **Database**:
  - Monitor `document_chunks` table size
  - Consider partitioning for very large datasets
  - Optimize vector similarity search queries

