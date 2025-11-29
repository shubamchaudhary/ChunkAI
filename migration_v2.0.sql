-- ============================================
-- DeepDocAI v2.0 Database Migration
-- Run this AFTER init.sql
-- ============================================

-- Add new columns to documents table for metadata
ALTER TABLE documents 
ADD COLUMN IF NOT EXISTS document_summary TEXT,
ADD COLUMN IF NOT EXISTS key_topics JSONB,
ADD COLUMN IF NOT EXISTS key_entities JSONB,
ADD COLUMN IF NOT EXISTS document_type VARCHAR(50),
ADD COLUMN IF NOT EXISTS summary_embedding vector(768);

-- Create index on summary_embedding for fast document-level search
CREATE INDEX IF NOT EXISTS idx_documents_summary_embedding ON documents 
USING hnsw (summary_embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- Add new columns to document_chunks table
ALTER TABLE document_chunks
ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(50),
ADD COLUMN IF NOT EXISTS key_terms TEXT[];

-- Create GIN index on key_terms for fast keyword search
CREATE INDEX IF NOT EXISTS idx_chunks_key_terms ON document_chunks 
USING gin(key_terms);

-- ============================================
-- QUERY_CACHE TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS query_cache (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    
    -- Query info
    query_text TEXT NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    query_embedding vector(768),
    
    -- Cached response
    response_text TEXT NOT NULL,
    sources_used JSONB,
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    hit_count INTEGER DEFAULT 0,
    
    CONSTRAINT unique_query_per_chat UNIQUE(chat_id, query_hash)
);

CREATE INDEX IF NOT EXISTS idx_cache_expiry ON query_cache(expires_at);
CREATE INDEX IF NOT EXISTS idx_cache_embedding ON query_cache 
USING hnsw (query_embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
CREATE INDEX IF NOT EXISTS idx_cache_chat_hash ON query_cache(chat_id, query_hash);

-- ============================================
-- API_KEY_USAGE TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS api_key_usage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    key_identifier VARCHAR(50) NOT NULL,
    
    -- Rate limiting
    minute_bucket TIMESTAMP WITH TIME ZONE NOT NULL,
    request_count INTEGER DEFAULT 0,
    token_count INTEGER DEFAULT 0,
    
    -- Daily tracking
    day_bucket DATE NOT NULL,
    daily_request_count INTEGER DEFAULT 0,
    
    -- Health
    last_success_at TIMESTAMP WITH TIME ZONE,
    last_failure_at TIMESTAMP WITH TIME ZONE,
    consecutive_failures INTEGER DEFAULT 0,
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT unique_key_minute UNIQUE(key_identifier, minute_bucket)
);

CREATE INDEX IF NOT EXISTS idx_key_usage_bucket ON api_key_usage(key_identifier, minute_bucket);
CREATE INDEX IF NOT EXISTS idx_key_usage_day ON api_key_usage(key_identifier, day_bucket);

-- Add llm_calls_used to query_history
ALTER TABLE query_history
ADD COLUMN IF NOT EXISTS llm_calls_used INTEGER DEFAULT 1;

