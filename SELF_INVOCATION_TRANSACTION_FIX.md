# Self-Invocation Transaction Fix

## Root Cause: Spring's Self-Invocation Limitation

### The Problem

The `TransactionRequiredException` was occurring because of Spring's **self-invocation** rule:

1. `DocumentProcessingService.processDocument()` (public) calls `saveChunksAndComplete()` (private) internally
2. `saveChunksAndComplete()` has `@Transactional` annotation
3. **But**: When a method calls another method **within the same class**, Spring bypasses the proxy
4. This means the `@Transactional` annotation on `saveChunksAndComplete()` is **ignored**
5. No transaction is created
6. Native SQL `executeUpdate()` fails with `TransactionRequiredException`

### Why "Document/Job Not Found" Errors?

Because the main transaction crashes and rolls back:
- The job status update is rolled back
- The document status update is rolled back
- When the error handler (running in a separate transaction) tries to find these records, they don't exist
- Result: "Job not found" and "Document not found" errors

---

## The Solution

### Strategy: Transaction at Repository Layer

**Place `@Transactional` on the Repository method** because:

1. **Repository is injected as a bean** - When `DocumentProcessingService` calls `customRepo.batchSaveChunksWithEmbeddings()`, it goes **through the Spring proxy**
2. **Proxy ensures transaction** - The `@Transactional` annotation on the repository method is properly processed
3. **Guaranteed transaction context** - Native SQL `executeUpdate()` will always have an active transaction

### Changes Made

**File**: `examprep-data/src/main/java/com/examprep/data/repository/DocumentChunkRepositoryImpl.java`

```java
import org.springframework.transaction.annotation.Transactional;  // Added import

@Override
@Transactional  // Added back - repository bean is called through proxy
public void batchSaveChunksWithEmbeddings(List<DocumentChunk> chunks) {
    // ... implementation
}
```

---

## Why This Works

### Spring Proxy Behavior

```
Service Layer (processDocument)
  ↓ [internal call - bypasses proxy]
  saveChunksAndComplete() [@Transactional ignored]
    ↓ [external call through bean proxy]
  Repository Bean (batchSaveChunksWithEmbeddings)
    ↓ [@Transactional processed by proxy]
  Transaction Created ✅
  Native SQL executeUpdate() ✅
```

### Key Points

1. **Repository is a Spring-managed bean** - Injected via `@Repository` annotation
2. **Calls go through proxy** - `customRepo.batchSaveChunksWithEmbeddings()` is called on the injected bean
3. **Transaction is created** - Spring's AOP proxy intercepts the call and creates the transaction
4. **Native SQL succeeds** - `executeUpdate()` has an active transaction context

---

## Expected Results

After this fix:

1. ✅ **TransactionRequiredException disappears** - Transaction is properly created at repository layer
2. ✅ **Batch inserts succeed** - Chunks are saved successfully
3. ✅ **"Job not found" errors disappear** - Main transaction completes, job is visible to error handler
4. ✅ **"Document not found" errors disappear** - Document updates are committed

---

## Testing Checklist

- [ ] Upload a document and verify chunks are saved
- [ ] Check logs for "Batch insert completed: X chunks saved"
- [ ] Verify no `TransactionRequiredException` errors
- [ ] Verify no "Job not found" errors in error handler
- [ ] Verify documents are properly marked as COMPLETED
- [ ] Check database: `SELECT COUNT(*) FROM document_chunks WHERE embedding IS NOT NULL;`

---

## Additional Context

### Why Service Layer `@Transactional` Didn't Work

The service method `saveChunksAndComplete()` is:
- **Private method** - Called internally from `processDocument()`
- **Self-invocation** - Method calling another method in the same class
- **Bypasses proxy** - Spring cannot intercept internal calls

### Why Repository Layer `@Transactional` Works

The repository method `batchSaveChunksWithEmbeddings()` is:
- **Called via injected bean** - `customRepo` is injected by Spring
- **Goes through proxy** - All calls to injected beans are intercepted
- **Transaction created** - Spring's AOP properly processes `@Transactional`

---

## Related Fixes

- **TRANSACTION_FIX_SUMMARY.md**: Previous attempt (removed repository transaction)
- **VECTOR_MAPPING_TRANSACTION_FIX.md**: Initial transaction fix
- **THREE_CRITICAL_FIXES.md**: Vector mapping fix (native SQL)

---

## Deployment Notes

- ✅ **No breaking changes** - Just adding back `@Transactional` annotation
- ✅ **No database changes** - Pure code fix
- ✅ **Backward compatible** - Same behavior, just working correctly now

Rebuild and deploy the application. The ingestion pipeline should now work correctly.

