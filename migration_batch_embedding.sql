-- Migration: Remove summary fields and add batch embedding support
-- This migration removes document summaries and adds processing tier tracking for batch embedding

-- Step 1: Remove summary-related columns from documents table
ALTER TABLE documents DROP COLUMN IF EXISTS document_summary;
ALTER TABLE documents DROP COLUMN IF EXISTS summary_embedding;
ALTER TABLE documents DROP COLUMN IF EXISTS key_topics;
ALTER TABLE documents DROP COLUMN IF EXISTS key_entities;
ALTER TABLE documents DROP COLUMN IF EXISTS document_type;

-- Step 2: Add processing tier tracking
ALTER TABLE documents ADD COLUMN IF NOT EXISTS processing_tier VARCHAR(20) DEFAULT 'PENDING';
-- Values: 'PENDING', 'EXTRACTING', 'CHUNKED', 'EMBEDDING', 'COMPLETED', 'FAILED'

-- Step 3: Add batch tracking fields
ALTER TABLE documents ADD COLUMN IF NOT EXISTS chunks_embedded INTEGER DEFAULT 0;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS total_chunks INTEGER DEFAULT 0;

-- Step 4: Update document_chunks table - make embedding nullable
ALTER TABLE document_chunks ALTER COLUMN embedding DROP NOT NULL;

-- Step 5: Add index for finding chunks without embeddings
CREATE INDEX IF NOT EXISTS idx_chunks_pending_embedding 
ON document_chunks(document_id, chat_id) 
WHERE embedding IS NULL;

-- Step 6: Add full-text search column for hybrid search
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS content_tsv tsvector;

-- Step 7: Create full-text search index
CREATE INDEX IF NOT EXISTS idx_chunks_fulltext 
ON document_chunks USING gin(content_tsv);

-- Step 8: Create trigger function to auto-update tsvector
CREATE OR REPLACE FUNCTION update_chunk_tsv() RETURNS trigger AS $$
BEGIN
  NEW.content_tsv := to_tsvector('english', COALESCE(NEW.content, ''));
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Step 9: Drop existing trigger if exists
DROP TRIGGER IF EXISTS chunk_tsv_update ON document_chunks;

-- Step 10: Create trigger to auto-update tsvector on insert/update
CREATE TRIGGER chunk_tsv_update 
  BEFORE INSERT OR UPDATE OF content ON document_chunks
  FOR EACH ROW EXECUTE FUNCTION update_chunk_tsv();

-- Step 11: Update existing chunks to populate tsvector
UPDATE document_chunks SET content_tsv = to_tsvector('english', COALESCE(content, ''))
WHERE content_tsv IS NULL;

-- Step 12: Drop old summary embedding index if it exists
DROP INDEX IF EXISTS idx_documents_summary_embedding;

-- Step 13: Update existing documents to set processing_tier based on processing_status
UPDATE documents 
SET processing_tier = CASE 
    WHEN processing_status = 'PENDING' THEN 'PENDING'
    WHEN processing_status = 'PROCESSING' THEN 'EXTRACTING'
    WHEN processing_status = 'COMPLETED' THEN 
        CASE 
            WHEN (SELECT COUNT(*) FROM document_chunks WHERE document_id = documents.id AND embedding IS NULL) = 0 
            THEN 'COMPLETED'
            ELSE 'EMBEDDING'
        END
    WHEN processing_status = 'FAILED' THEN 'FAILED'
    ELSE 'PENDING'
END
WHERE processing_tier IS NULL OR processing_tier = 'PENDING';

-- Step 14: Set total_chunks and chunks_embedded for existing documents
UPDATE documents d
SET 
    total_chunks = (SELECT COUNT(*) FROM document_chunks WHERE document_id = d.id),
    chunks_embedded = (SELECT COUNT(*) FROM document_chunks WHERE document_id = d.id AND embedding IS NOT NULL)
WHERE total_chunks IS NULL OR total_chunks = 0;

-- Step 15: Add comment for documentation
COMMENT ON COLUMN documents.processing_tier IS 'Processing tier: PENDING, EXTRACTING, CHUNKED, EMBEDDING, COMPLETED, FAILED';
COMMENT ON COLUMN documents.chunks_embedded IS 'Number of chunks that have been embedded';
COMMENT ON COLUMN documents.total_chunks IS 'Total number of chunks for this document';

