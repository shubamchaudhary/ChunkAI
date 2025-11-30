# Vector Mapping Transaction Fix

## Issue: Missing Transaction for Native SQL

### Error
```
jakarta.persistence.TransactionRequiredException: Executing an update/delete query
Location: DocumentChunkRepositoryImpl.batchSaveChunksWithEmbeddings
```

### Root Cause
When using native SQL with `entityManager.createNativeQuery(...).executeUpdate()`, Hibernate requires an explicit transaction context. Even though the calling service method (`DocumentProcessingService.saveChunksAndComplete()`) has `@Transactional`, the repository method itself also needs the annotation to ensure the transaction is properly bound to the EntityManager when executing native SQL INSERT statements.

### Solution
Added `@Transactional` annotation to `batchSaveChunksWithEmbeddings()` method in `DocumentChunkRepositoryImpl.java`.

### Changes Made

**File**: `examprep-data/src/main/java/com/examprep/data/repository/DocumentChunkRepositoryImpl.java`

1. **Added import**:
```java
import org.springframework.transaction.annotation.Transactional;
```

2. **Added annotation to method**:
```java
@Override
@Transactional  // Required for native SQL executeUpdate() calls
public void batchSaveChunksWithEmbeddings(List<DocumentChunk> chunks) {
    // ... method implementation
}
```

### Why This Works

- **Standard JPA methods** (`save()`, `saveAll()`) automatically inherit transaction context from the calling service
- **Native SQL** with `executeUpdate()` bypasses some of Spring Data's transaction magic
- **Explicit `@Transactional`** ensures the EntityManager has a writable transaction bound to it
- This allows the native INSERT statements to execute successfully

### Testing

1. Upload a document and verify chunks are saved
2. Check database: `SELECT COUNT(*) FROM document_chunks WHERE embedding IS NOT NULL;`
3. Verify no `TransactionRequiredException` errors in logs

### Related Fixes

This complements the vector mapping fix from `THREE_CRITICAL_FIXES.md`:
- The native SQL approach avoids Hibernate's float[] to pgvector mapping issues
- Adding `@Transactional` ensures the native SQL can execute properly

---

**Status**: ✅ Fixed and ready for deployment

