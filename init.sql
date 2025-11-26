-- ============================================
-- EXAMPREP AI DATABASE SCHEMA
-- Run this in Railway PostgreSQL or local Docker
-- ============================================

-- Ensure the examprep user exists and password is set correctly
-- This handles cases where POSTGRES_USER creates the user but password auth fails
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_user WHERE usename = 'examprep') THEN
        CREATE USER examprep WITH PASSWORD 'examprep123' SUPERUSER;
    ELSE
        ALTER USER examprep WITH PASSWORD 'examprep123';
    END IF;
END
$$;

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- ============================================
-- USERS TABLE
-- ============================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_login_at TIMESTAMP WITH TIME ZONE,
    is_active BOOLEAN DEFAULT TRUE
);

-- Index for email lookups (login)
CREATE INDEX idx_users_email ON users(email);

-- ============================================
-- DOCUMENTS TABLE
-- Stores metadata about uploaded files
-- ============================================
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- File metadata
    file_name VARCHAR(500) NOT NULL,
    original_file_name VARCHAR(500) NOT NULL,
    file_type VARCHAR(50) NOT NULL,  -- 'ppt', 'pptx', 'pdf', 'image', 'txt'
    file_size_bytes BIGINT NOT NULL,
    mime_type VARCHAR(100),
    
    -- Processing info
    total_pages INTEGER,
    total_chunks INTEGER DEFAULT 0,
    processing_status VARCHAR(20) DEFAULT 'PENDING',
    -- Statuses: PENDING, PROCESSING, COMPLETED, FAILED
    processing_started_at TIMESTAMP WITH TIME ZONE,
    processing_completed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT valid_status CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Indexes for common queries
CREATE INDEX idx_documents_user_id ON documents(user_id);
CREATE INDEX idx_documents_user_status ON documents(user_id, processing_status);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);

-- ============================================
-- DOCUMENT_CHUNKS TABLE
-- Stores text chunks with vector embeddings
-- ============================================
CREATE TABLE document_chunks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Foreign keys
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- Denormalized for fast filtering
    
    -- Chunk content
    chunk_index INTEGER NOT NULL,  -- Order within document
    content TEXT NOT NULL,
    content_hash VARCHAR(64),  -- For deduplication
    
    -- Source location
    page_number INTEGER,
    slide_number INTEGER,
    section_title VARCHAR(500),
    
    -- Vector embedding (768 dimensions for text-embedding-004)
    embedding vector(768),
    
    -- Metadata
    token_count INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT unique_chunk_per_doc UNIQUE(document_id, chunk_index)
);

-- CRITICAL: HNSW index for fast vector similarity search
-- m = 16: Number of connections per layer (higher = more accurate, slower build)
-- ef_construction = 64: Size of dynamic candidate list during construction
CREATE INDEX idx_chunks_embedding ON document_chunks 
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- Index for user-scoped queries (CRITICAL for multi-tenancy)
CREATE INDEX idx_chunks_user_id ON document_chunks(user_id);

-- Composite index for user + document queries
CREATE INDEX idx_chunks_user_document ON document_chunks(user_id, document_id);

-- ============================================
-- PROCESSING_JOBS TABLE
-- Queue for async document processing
-- ============================================
CREATE TABLE processing_jobs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    
    -- Job status
    status VARCHAR(20) DEFAULT 'QUEUED',
    -- Statuses: QUEUED, PROCESSING, COMPLETED, FAILED
    priority INTEGER DEFAULT 5,  -- 1 = highest, 10 = lowest
    
    -- Retry logic
    attempts INTEGER DEFAULT 0,
    max_attempts INTEGER DEFAULT 3,
    last_error TEXT,
    
    -- Locking for worker coordination
    locked_by VARCHAR(100),
    locked_until TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT valid_job_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Index for fetching next job (priority queue)
CREATE INDEX idx_jobs_queue ON processing_jobs(status, priority, created_at) 
WHERE status = 'QUEUED';

-- ============================================
-- QUERY_HISTORY TABLE
-- Stores user queries for analytics and caching
-- ============================================
CREATE TABLE query_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- Query details
    query_text TEXT NOT NULL,
    query_embedding vector(768),
    marks_requested INTEGER,
    
    -- Response details
    answer_text TEXT,
    sources_used JSONB,  -- Array of {document_id, chunk_id, page}
    
    -- Performance metrics
    retrieval_time_ms INTEGER,
    generation_time_ms INTEGER,
    total_time_ms INTEGER,
    chunks_retrieved INTEGER,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for user query history
CREATE INDEX idx_query_history_user ON query_history(user_id, created_at DESC);

-- ============================================
-- HELPER FUNCTIONS
-- ============================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to tables with updated_at
CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_documents_updated_at
    BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- VECTOR SEARCH FUNCTION
-- Performs similarity search with user isolation
-- ============================================
CREATE OR REPLACE FUNCTION search_similar_chunks(
    p_user_id UUID,
    p_query_embedding vector(768),
    p_limit INTEGER DEFAULT 10,
    p_document_ids UUID[] DEFAULT NULL
)
RETURNS TABLE (
    chunk_id UUID,
    document_id UUID,
    content TEXT,
    page_number INTEGER,
    slide_number INTEGER,
    section_title VARCHAR(500),
    file_name VARCHAR(500),
    similarity FLOAT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        dc.id AS chunk_id,
        dc.document_id,
        dc.content,
        dc.page_number,
        dc.slide_number,
        dc.section_title,
        d.file_name,
        1 - (dc.embedding <=> p_query_embedding) AS similarity
    FROM document_chunks dc
    JOIN documents d ON dc.document_id = d.id
    WHERE dc.user_id = p_user_id
      AND d.processing_status = 'COMPLETED'
      AND (p_document_ids IS NULL OR dc.document_id = ANY(p_document_ids))
    ORDER BY dc.embedding <=> p_query_embedding
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql;

