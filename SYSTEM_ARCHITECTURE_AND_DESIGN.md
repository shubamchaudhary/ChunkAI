# DeepDocAI - Complete System Architecture & Design Documentation

## Table of Contents
1. [System Overview](#system-overview)
2. [Architecture](#architecture)
3. [Technology Stack](#technology-stack)
4. [Module Structure](#module-structure)
5. [Database Schema](#database-schema)
6. [Data Flow](#data-flow)
7. [API Endpoints](#api-endpoints)
8. [Core Components](#core-components)
9. [Processing Pipelines](#processing-pipelines)
10. [Authentication & Security](#authentication--security)
11. [Frontend Architecture](#frontend-architecture)
12. [Configuration](#configuration)
13. [Key Algorithms](#key-algorithms)

---

## System Overview

**DeepDocAI** is an AI-powered document analysis and Q&A system similar to ChatGPT/Claude, but with:
- **High context window**: Up to 1M input tokens, 100 document chunks, 50 conversation history pairs
- **Large file upload capacity**: Up to 1GB per file, 2GB per request
- **RAG (Retrieval Augmented Generation)**: Answers questions using uploaded documents + internet search
- **Multi-chat support**: Users can create multiple chat sessions, each with its own document set
- **Vector similarity search**: Uses pgvector for semantic document search

### Key Features
- 📚 Upload documents (PDF, PPT, PPTX, TXT, images with OCR)
- 💬 Chat-based Q&A interface
- 🔍 Semantic search across documents
- 🌐 Internet search integration (Google Search via Gemini)
- 📝 Conversation history with context retention
- 🔐 JWT-based authentication
- ⚡ Async document processing with batch support (10 parallel jobs)

---

## Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Frontend (React)                      │
│  - Login/Register                                           │
│  - Chat Interface                                           │
│  - File Upload                                              │
│  - Query Interface                                          │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTP/REST API
┌──────────────────────▼──────────────────────────────────────┐
│                    API Layer (Spring Boot)                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ AuthController│  │DocumentCtrl │  │ QueryController│    │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                  │                  │              │
│  ┌──────▼─────────────────▼──────────────────▼──────┐     │
│  │         Business Logic Layer (Services)            │     │
│  │  - DocumentProcessingService                       │     │
│  │  - RagService                                      │     │
│  │  - EmbeddingService                                │     │
│  │  - ChunkingService                                 │     │
│  └──────┬─────────────────┬──────────────────┬───────┘     │
└─────────┼──────────────────┼──────────────────┼────────────┘
          │                  │                  │
┌─────────▼──────────────────▼──────────────────▼────────────┐
│              Data Access Layer (JPA Repositories)          │
│  - UserRepository                                          │
│  - DocumentRepository                                      │
│  - DocumentChunkRepository                                │
│  - QueryHistoryRepository                                  │
│  - ChatRepository                                          │
└──────────────────────┬─────────────────────────────────────┘
                       │ JDBC
┌──────────────────────▼──────────────────────────────────────┐
│              PostgreSQL + pgvector Database                │
│  - users, chats, documents, document_chunks                │
│  - query_history, processing_jobs                           │
│  - Vector embeddings (768 dimensions)                     │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              External Services                              │
│  ┌──────────────────┐  ┌──────────────────┐               │
│  │  Gemini API      │  │  File Storage    │               │
│  │  - Embeddings    │  │  (Local FS)      │               │
│  │  - Generation    │  │                  │               │
│  │  - Google Search │  │                  │               │
│  └──────────────────┘  └──────────────────┘               │
└─────────────────────────────────────────────────────────────┘
```

### Module Structure (Gradle Multi-Module)

```
examprep-ai/
├── examprep-api/          # REST API module (entry point)
│   ├── controllers/        # REST endpoints
│   ├── dto/                # Request/Response DTOs
│   ├── security/           # JWT authentication
│   └── config/             # Spring configuration
│
├── examprep-core/         # Business logic & document processing
│   ├── service/            # Core services
│   │   ├── DocumentProcessingService
│   │   ├── ChunkingService
│   │   ├── FileStorageService
│   │   └── ProcessingJobWorker
│   └── processor/          # Document processors
│       ├── PdfProcessor
│       ├── PptProcessor
│       ├── TextProcessor
│       └── ImageProcessor (OCR)
│
├── examprep-llm/          # LLM integration
│   ├── client/             # Gemini API client
│   │   ├── GeminiClient
│   │   └── GeminiConfig
│   ├── service/            # AI services
│   │   ├── RagService
│   │   └── EmbeddingService
│   └── prompt/             # Prompt templates
│
├── examprep-data/         # Data access layer
│   ├── entity/             # JPA entities
│   │   ├── User
│   │   ├── Chat
│   │   ├── Document
│   │   ├── DocumentChunk
│   │   ├── QueryHistory
│   │   └── ProcessingJob
│   └── repository/         # Spring Data JPA repositories
│       ├── UserRepository
│       ├── DocumentRepository
│       ├── DocumentChunkRepository
│       └── QueryHistoryRepository
│
└── examprep-common/       # Shared utilities
    ├── constants/          # Constants (FileTypes, ProcessingStatus)
    └── util/                # Utilities (FileUtils, TokenCounter)
```

---

## Technology Stack

### Backend
- **Framework**: Spring Boot 3.2
- **Build Tool**: Gradle (Kotlin DSL)
- **Language**: Java 17/21
- **Database**: PostgreSQL 15+ with pgvector extension
- **ORM**: Spring Data JPA / Hibernate
- **Security**: Spring Security + JWT
- **Async Processing**: Spring @Scheduled + ExecutorService

### LLM & AI
- **LLM**: Google Gemini 2.5 Flash
  - Input context: Up to 1M tokens
  - Output tokens: Up to 8,192 tokens
  - Google Search grounding enabled
- **Embeddings**: Google text-embedding-004 (768 dimensions)
- **Vector DB**: PostgreSQL pgvector with HNSW index

### File Processing
- **PDF**: Apache PDFBox
- **PowerPoint**: Apache POI
- **OCR**: Tesseract (for images)
- **Text**: Java Scanner

### Frontend
- **Framework**: React 18
- **Build Tool**: Vite
- **Styling**: Tailwind CSS
- **HTTP Client**: Axios
- **Routing**: React Router

### Infrastructure
- **Database**: Docker Compose (PostgreSQL)
- **File Storage**: Local filesystem (`./uploads`)
- **Deployment**: Railway (backend), Vercel (frontend - planned)

---

## Database Schema

### Tables

#### 1. `users`
```sql
- id (UUID, PK)
- email (VARCHAR(255), UNIQUE)
- password_hash (VARCHAR(255))
- full_name (VARCHAR(255))
- created_at, updated_at, last_login_at (TIMESTAMP)
- is_active (BOOLEAN)
```

#### 2. `chats`
```sql
- id (UUID, PK)
- user_id (UUID, FK → users.id, CASCADE DELETE)
- title (TEXT) -- Can be long, not truncated
- created_at, updated_at (TIMESTAMP)
```

#### 3. `documents`
```sql
- id (UUID, PK)
- user_id (UUID, FK → users.id, CASCADE DELETE)
- chat_id (UUID, FK → chats.id, CASCADE DELETE)
- file_name, original_file_name (VARCHAR(500))
- file_type (VARCHAR(50)) -- 'pdf', 'ppt', 'pptx', 'txt', 'png', etc.
- file_size_bytes (BIGINT)
- processing_status (VARCHAR(20)) -- 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'
- total_pages, total_chunks (INTEGER)
- processing_started_at, processing_completed_at (TIMESTAMP)
- error_message (TEXT)
- created_at, updated_at (TIMESTAMP)

Indexes:
- idx_documents_user_id
- idx_documents_chat_id
- idx_documents_user_chat
- idx_documents_chat_filename_size (for duplicate detection)
```

#### 4. `document_chunks`
```sql
- id (UUID, PK)
- document_id (UUID, FK → documents.id, CASCADE DELETE)
- user_id (UUID, FK → users.id, denormalized for fast filtering)
- chat_id (UUID, FK → chats.id, denormalized for fast filtering)
- chunk_index (INTEGER) -- Order within document
- content (TEXT) -- The actual text chunk
- content_hash (VARCHAR(64)) -- SHA-256 for deduplication
- page_number, slide_number (INTEGER)
- section_title (VARCHAR(500))
- embedding (vector(768)) -- pgvector type, 768 dimensions
- token_count (INTEGER)
- created_at (TIMESTAMP)

Constraints:
- UNIQUE(document_id, chunk_index)

Indexes:
- idx_chunks_embedding (HNSW index for vector similarity search)
- idx_chunks_user_id
- idx_chunks_user_chat
- idx_chunks_user_document
```

#### 5. `processing_jobs`
```sql
- id (UUID, PK)
- document_id (UUID, FK → documents.id, CASCADE DELETE)
- status (VARCHAR(20)) -- 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'
- priority (INTEGER) -- 1 = highest, 10 = lowest
- attempts, max_attempts (INTEGER)
- last_error (TEXT)
- locked_by (VARCHAR(100)) -- Worker ID
- locked_until (TIMESTAMP)
- created_at, started_at, completed_at (TIMESTAMP)

Indexes:
- idx_jobs_queue (status, priority, created_at) WHERE status = 'QUEUED'
```

#### 6. `query_history`
```sql
- id (UUID, PK)
- user_id (UUID, FK → users.id, CASCADE DELETE)
- chat_id (UUID, FK → chats.id, CASCADE DELETE)
- query_text (TEXT)
- query_embedding (vector(768))
- marks_requested (INTEGER)
- answer_text (TEXT) -- Full answer, not truncated
- sources_used (JSONB) -- Array of {documentId, fileName, pageNumber, slideNumber}
- retrieval_time_ms, generation_time_ms, total_time_ms (INTEGER)
- chunks_retrieved (INTEGER)
- created_at (TIMESTAMP)

Indexes:
- idx_query_history_user (user_id, created_at DESC)
```

### Key Database Features

1. **Cascade Deletes**: Deleting a user deletes all their chats, documents, chunks, and query history
2. **Chat Scoping**: Documents and chunks are scoped to chats (chat_id)
3. **Vector Search**: HNSW index on `document_chunks.embedding` for fast similarity search
4. **Duplicate Detection**: Unique constraint on `(document_id, chunk_index)` and index on `(chat_id, original_file_name, file_size_bytes)`
5. **Multi-tenancy**: All queries filtered by `user_id` for security

---

## Data Flow

### 1. User Registration Flow

```
User → POST /api/v1/auth/register
  → AuthController.register()
  → UserRepository.save()
  → Password hashing (BCrypt)
  → JWT token generation
  → Return token + user info
```

### 2. Document Upload Flow

```
User → POST /api/v1/documents/upload (multipart/form-data)
  → DocumentController.uploadDocument()
  → Validate file (size, type, duplicate check)
  → FileStorageService.saveFile() → Save to ./uploads/{documentId}.{ext}
  → Create Document entity (status: PENDING)
  → Create ProcessingJob (status: QUEUED)
  → Return 202 Accepted with document metadata
  
Background:
  → ProcessingJobWorker.processQueuedJobs() (runs every 2 seconds)
  → Lock job (pessimistic lock)
  → DocumentProcessingService.processDocument()
    → Load file from storage
    → DocumentProcessor.extract() (PDF/PPT/TXT/Image)
    → ChunkingService.chunkByPages()
    → For each chunk:
      → EmbeddingService.generateEmbedding() → Gemini API
      → Save DocumentChunk with embedding
    → Update Document status to COMPLETED
  → Unlock job
```

### 3. Query Flow (RAG Pipeline)

```
User → POST /api/v1/query
  → QueryController.query()
  → Check if documents are still processing (block if yes)
  → Load conversation history (last 50 Q&A pairs)
  
  → RagService.query()
    
    STAGE 1: Vector Retrieval
      → EmbeddingService.generateEmbedding(question)
      → DocumentChunkRepository.findSimilarChunksCustom()
        → Native SQL query with pgvector cosine similarity
        → Filter by user_id, chat_id (or cross-chat), document_ids
        → Return top 100 chunks (or 20 for follow-up questions)
      → Filter chunks by conversation history documents (for follow-ups)
    
    STAGE 2: Prompt Creation (First LLM Call)
      → Build conversation context (last 50 Q&A pairs)
      → Build document context from retrieved chunks
      → Create prompt creation system/user prompts
      → GeminiClient.generateContent(promptCreationPrompt, useGoogleSearch=true)
      → Returns optimized prompt
    
    STAGE 3: Answer Generation (Second LLM Call)
      → GeminiClient.generateContent(optimizedPrompt, systemPrompt, useGoogleSearch=true)
      → Returns final answer with citations
    
    → Build RagResult with answer + sources
    → Save to QueryHistory
    → Return QueryResponse
```

### 4. Chat Management Flow

```
Create Chat:
  User → POST /api/v1/chats
    → ChatController.createChat()
    → Create Chat entity
    → Return chat info

Delete Chat:
  User → DELETE /api/v1/chats/{id}
    → ChatController.deleteChat()
    → Delete all documents (cascades to chunks)
    → Delete all query history
    → Delete chat
    → Return 204 No Content
```

---

## API Endpoints

### Authentication

#### `POST /api/v1/auth/register`
```json
Request:
{
  "email": "user@example.com",
  "password": "password123",
  "fullName": "John Doe"
}

Response:
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "fullName": "John Doe"
  }
}
```

#### `POST /api/v1/auth/login`
```json
Request:
{
  "email": "user@example.com",
  "password": "password123"
}

Response: Same as register
```

### Chats

#### `GET /api/v1/chats`
- Query params: `page`, `size`
- Returns: Paginated list of user's chats

#### `POST /api/v1/chats`
```json
Request:
{
  "title": "New Chat"
}

Response:
{
  "id": "uuid",
  "title": "New Chat",
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:00:00Z"
}
```

#### `DELETE /api/v1/chats/{id}`
- Deletes chat and all associated documents/queries

### Documents

#### `POST /api/v1/documents/upload`
- Content-Type: `multipart/form-data`
- Params: `file` (MultipartFile), `chatId` (UUID)
- Returns: Document metadata (status: PENDING)
- Max file size: 1GB

#### `POST /api/v1/documents/bulk-upload`
- Content-Type: `multipart/form-data`
- Params: `files[]` (MultipartFile[]), `chatId` (UUID)
- Returns: List of uploaded documents + duplicates list
- Processes up to 10 files in parallel

#### `GET /api/v1/documents`
- Query params: `chatId` (UUID), `page`, `size`
- Returns: Paginated list of documents

#### `GET /api/v1/documents/{id}`
- Returns: Document details + processing status

#### `DELETE /api/v1/documents/{id}?chatId={chatId}`
- Deletes document and all chunks

### Query

#### `POST /api/v1/query`
```json
Request:
{
  "question": "What is machine learning?",
  "chatId": "uuid",
  "marks": 10,  // Optional
  "formatInstructions": "Use bullet points",  // Optional
  "documentIds": ["uuid1", "uuid2"],  // Optional, filter to specific docs
  "useCrossChat": false  // Optional, search across all user docs
}

Response:
{
  "answer": "Machine learning is...",
  "sources": [
    {
      "documentId": "uuid",
      "fileName": "ml_book.pdf",
      "pageNumber": 5,
      "slideNumber": null,
      "excerpt": "Machine learning is a subset..."
    }
  ],
  "metadata": {
    "retrievalTimeMs": 150,
    "generationTimeMs": 2500,
    "totalTimeMs": 2650,
    "chunksUsed": 10
  }
}
```

#### `GET /api/v1/query/history`
- Query params: `chatId` (UUID, optional), `page`, `size`
- Returns: Paginated query history

### User

#### `GET /api/v1/user/profile`
- Returns: User profile + statistics

#### `GET /api/v1/user/storage`
- Returns: Storage usage statistics

---

## Core Components

### 1. DocumentProcessingService

**Purpose**: Orchestrates document extraction, chunking, and embedding generation.

**Key Methods**:
- `processDocument(UUID documentId)`: Main processing pipeline
  1. Load document from DB
  2. Get appropriate processor (PDF/PPT/TXT/Image)
  3. Extract text from file
  4. Chunk text by pages
  5. Generate embeddings for each chunk
  6. Save chunks to database
  7. Update document status

**Dependencies**:
- DocumentProcessorFactory
- ChunkingService
- EmbeddingService
- FileStorageService
- DocumentRepository
- DocumentChunkRepository

### 2. RagService

**Purpose**: Implements RAG (Retrieval Augmented Generation) pipeline.

**Key Methods**:
- `query(...)`: Main RAG pipeline
  - Stage 1: Vector retrieval (semantic search)
  - Stage 2: Prompt creation (first LLM call)
  - Stage 3: Answer generation (second LLM call)

**Features**:
- Two-stage LLM prompting for better answers
- Conversation history integration (up to 50 Q&A pairs)
- Document filtering for follow-up questions
- Google Search integration for missing information
- Source attribution

**Configuration**:
- `maxContextChunks`: 100 (configurable)
- `maxConversationHistory`: 50 Q&A pairs (configurable)
- `maxOutputTokens`: 8,192 (Gemini limit)

### 3. ProcessingJobWorker

**Purpose**: Background worker that processes queued document jobs.

**Key Features**:
- Runs every 2 seconds (`@Scheduled`)
- Processes up to 10 jobs in parallel (batch processing)
- Uses pessimistic locking to prevent concurrent processing
- Retry logic (up to 3 attempts)
- Error handling and job status updates

**Flow**:
1. Find next queued jobs (up to 10)
2. Submit to ExecutorService (parallel processing)
3. Each job runs in separate transaction
4. Lock job → Process → Unlock job

### 4. EmbeddingService

**Purpose**: Generates vector embeddings for text.

**Key Methods**:
- `generateEmbedding(String text)`: Calls Gemini API
- `toVectorString(float[] embedding)`: Converts to PostgreSQL vector format

**API**: Google text-embedding-004
- Dimensions: 768
- Model: `text-embedding-004`

### 5. GeminiClient

**Purpose**: Client for interacting with Gemini API.

**Key Methods**:
- `generateEmbedding(String text)`: Generate embeddings
- `generateContent(String prompt, String systemInstruction, boolean useGoogleSearch)`: Generate text

**Features**:
- Google Search grounding (when `useGoogleSearch=true`)
- Configurable max output tokens
- Error handling and retries
- Timeout configuration (60 seconds)

### 6. Document Processors

**Purpose**: Extract text from different file formats.

**Processors**:
- **PdfProcessor**: Uses Apache PDFBox
- **PptProcessor**: Uses Apache POI
- **TextProcessor**: Simple text file reader
- **ImageProcessor**: Uses Tesseract OCR

**Interface**:
```java
ExtractionResult extract(InputStream inputStream, String fileType)
  → Returns: List of page contents, page titles, total pages
```

### 7. ChunkingService

**Purpose**: Splits document text into chunks.

**Strategy**: Chunk by pages/slides
- Each page/slide becomes one chunk
- Preserves page/slide numbers
- Extracts section titles

**Output**: `ChunkingResult` with:
- Content
- Page/slide number
- Section title
- Token count

---

## Processing Pipelines

### Document Processing Pipeline

```
1. Upload
   └─> File saved to ./uploads/{documentId}.{ext}
   └─> Document entity created (PENDING)
   └─> ProcessingJob created (QUEUED)

2. Worker picks up job
   └─> Lock job (PROCESSING)
   └─> Load file from storage

3. Text Extraction
   └─> PdfProcessor/PptProcessor/TextProcessor/ImageProcessor
   └─> Extract text per page/slide
   └─> Return ExtractionResult

4. Chunking
   └─> ChunkingService.chunkByPages()
   └─> One chunk per page/slide
   └─> Preserve page/slide numbers

5. Embedding Generation (for each chunk)
   └─> EmbeddingService.generateEmbedding(chunk.content)
   └─> Call Gemini API (text-embedding-004)
   └─> Get 768-dimensional vector

6. Save Chunks
   └─> Create DocumentChunk entity
   └─> Save to database with embedding

7. Update Status
   └─> Document status → COMPLETED
   └─> ProcessingJob status → COMPLETED
   └─> Unlock job
```

### RAG Query Pipeline

```
1. Query Received
   └─> Validate chat ownership
   └─> Check if documents are processing (block if yes)
   └─> Load conversation history (last 50 Q&A pairs)

2. Stage 1: Vector Retrieval
   └─> Generate query embedding (768 dimensions)
   └─> Convert to PostgreSQL vector string
   └─> Execute similarity search:
       SELECT ... FROM document_chunks
       WHERE user_id = ? AND chat_id = ? (or cross-chat)
       ORDER BY embedding <=> query_embedding
       LIMIT 100 (or 20 for follow-ups)
   └─> Filter chunks by conversation history documents (for follow-ups)

3. Stage 2: Prompt Creation (First LLM Call)
   └─> Build conversation context string
   └─> Build document context string from chunks
   └─> Create prompt creation system/user prompts
   └─> Call Gemini API (with Google Search enabled)
   └─> Get optimized prompt

4. Stage 3: Answer Generation (Second LLM Call)
   └─> Use optimized prompt + system prompt
   └─> Call Gemini API (with Google Search enabled)
   └─> Get final answer

5. Post-Processing
   └─> Extract sources from filtered chunks
   └─> Build RagResult
   └─> Save to QueryHistory
   └─> Return QueryResponse
```

---

## Authentication & Security

### JWT Authentication

**Flow**:
1. User registers/logs in
2. Server generates JWT token (expires in 24 hours)
3. Client stores token (localStorage)
4. Client sends token in `Authorization: Bearer {token}` header
5. Spring Security validates token on each request

**JWT Claims**:
- `sub`: User ID (UUID)
- `exp`: Expiration time
- `iat`: Issued at time

### Security Configuration

**Spring Security**:
- All `/api/v1/**` endpoints require authentication
- `/api/v1/auth/**` endpoints are public
- CORS enabled for frontend origin
- Password hashing: BCrypt (strength 10)

**Multi-tenancy**:
- All queries filtered by `user_id`
- Users can only access their own chats/documents
- Chat ownership verified before operations

---

## Frontend Architecture

### Component Structure

```
src/
├── pages/
│   ├── Login.jsx              # Login page
│   ├── Register.jsx           # Registration page
│   └── AppLayout.jsx           # Main app layout
│
├── components/
│   ├── ChatSidebar.jsx        # Left sidebar with chat list
│   ├── FileUpload.jsx          # File upload component
│   ├── FileList.jsx            # List of uploaded files
│   ├── FileViewer.jsx          # File preview/viewer
│   └── QueryInterface.jsx     # Chat interface (Q&A)
│
├── contexts/
│   └── AuthContext.jsx        # Authentication context
│
└── services/
    └── api.js                 # API client (Axios)
```

### Key Features

1. **Chat-based UI**: Similar to ChatGPT/Claude
   - Left sidebar: Chat list (fixed width, scrollable)
   - Main area: Chat messages + input
   - Top bar: File upload toggle

2. **File Management**:
   - Collapsible file panel
   - Upload multiple files (up to 100)
   - Show processing status
   - Delete files

3. **Query Interface**:
   - Markdown rendering (headers, bold, lists)
   - Source attribution display
   - Loading states
   - Error handling

4. **State Management**:
   - React Context for authentication
   - Local state for chats/documents
   - Polling for document status updates

### API Integration

**Base URL**: `http://localhost:8080/api/v1`

**Authentication**:
- Token stored in localStorage
- Sent in `Authorization` header
- Auto-redirect to login on 401

**Error Handling**:
- Network errors → Show error message
- 401 → Redirect to login
- 403 → Show permission error
- 400 → Show validation error

---

## Configuration

### Application Properties

```properties
# Server
server.port=8080
server.tomcat.max-http-form-post-size=1GB
spring.codec.max-in-memory-size=1GB

# Database
spring.datasource.url=jdbc:postgresql://127.0.0.1:5434/examprep_db
spring.datasource.username=examprep
spring.datasource.password=examprep123

# File Upload
spring.servlet.multipart.max-file-size=1GB
spring.servlet.multipart.max-request-size=2GB
spring.servlet.multipart.file-size-threshold=2MB

# Gemini API
gemini.api-key=YOUR_API_KEY
gemini.embedding-model=text-embedding-004
gemini.generation-model=gemini-2.5-flash
gemini.max-output-tokens=8192
gemini.max-context-chunks=100
gemini.max-conversation-history=50
gemini.timeout-seconds=60

# JWT
jwt.secret=your-secret-key-change-in-production
jwt.expiration=86400000  # 24 hours

# File Storage
file.storage.directory=./uploads
```

### Environment Variables

- `GEMINI_API_KEY`: Google Gemini API key (required)
- `JWT_SECRET`: JWT secret key (optional, defaults to property)
- `TESSDATA_PREFIX`: Tesseract OCR data path (for image OCR)

---

## Key Algorithms

### 1. Vector Similarity Search

**Algorithm**: Cosine similarity using pgvector

**Query**:
```sql
SELECT dc.* FROM document_chunks dc
JOIN documents d ON dc.document_id = d.id
WHERE dc.user_id = :userId
  AND dc.chat_id = :chatId  -- or omitted for cross-chat
  AND d.processing_status = 'COMPLETED'
ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
LIMIT :limit
```

**Index**: HNSW (Hierarchical Navigable Small World)
- `m = 16`: Connections per layer
- `ef_construction = 64`: Candidate list size
- `vector_cosine_ops`: Cosine similarity operator

**Performance**: O(log n) for similarity search

### 2. Document Chunking

**Strategy**: Page-based chunking
- One chunk per page/slide
- Preserves page/slide numbers
- Extracts section titles

**Token Counting**: Approximate (4 characters ≈ 1 token)

### 3. Follow-up Question Detection

**Heuristics**:
- Contains pronouns: "it", "this", "that"
- Contains references: "who wrote", "the book", "the author"
- Contains follow-up phrases: "what is it", "tell me more"

**Filtering**:
- Extract document names from conversation history
- Filter retrieved chunks to only include those documents
- Fuzzy matching on document names (normalize underscores, hyphens)

### 4. Duplicate Detection

**Strategy**: Filename + file size check per chat
- Query: `findByChatIdAndOriginalFileNameAndFileSizeBytes()`
- If match found → Return existing document (don't re-upload)

### 5. Batch Processing

**Strategy**: Process up to 10 jobs in parallel
- ExecutorService with fixed thread pool (10 threads)
- Each job runs in separate transaction (`REQUIRES_NEW`)
- Prevents deadlocks and ensures isolation

---

## Deployment

### Local Development

1. **Start PostgreSQL**:
   ```bash
   docker-compose up -d
   ```

2. **Run Backend**:
   ```bash
   ./gradlew :examprep-api:bootRun
   ```

3. **Run Frontend**:
   ```bash
   cd examprep-frontend
   npm install
   npm run dev
   ```

### Production Deployment

**Backend (Railway)**:
1. Create Railway project
2. Add PostgreSQL service (with pgvector)
3. Deploy Spring Boot app
4. Set environment variables

**Frontend (Vercel)**:
1. Connect GitHub repo
2. Set build command: `npm run build`
3. Set output directory: `dist`
4. Set environment variables (API URL)

---

## Performance Considerations

### Database
- **Vector Index**: HNSW index for fast similarity search
- **Composite Indexes**: User + chat, user + document for filtering
- **Connection Pooling**: HikariCP (default Spring Boot)

### Caching
- **Embeddings**: Not cached (always fresh)
- **Document Chunks**: Cached in memory during query processing
- **Query History**: Loaded from DB each time (could be cached)

### Scalability
- **Horizontal Scaling**: Stateless API (can scale horizontally)
- **File Storage**: Currently local FS (consider S3/GCS for production)
- **Database**: PostgreSQL can be scaled (read replicas for queries)

### Optimization Opportunities
1. Cache frequently accessed chunks
2. Implement streaming responses (SSE) for long answers
3. Add Redis for session/query caching
4. Use CDN for file storage
5. Implement query result caching

---

## Error Handling

### Document Processing Errors
- **OCR Failures**: Logged, returns empty text
- **Processing Failures**: Document marked as FAILED, error message saved
- **Retry Logic**: Up to 3 attempts per job

### Query Errors
- **No Documents**: Returns empty answer (can use internet search)
- **Processing Documents**: Returns 400 error asking user to wait
- **API Errors**: Logged, returns error message to user

### Authentication Errors
- **Invalid Token**: 401 Unauthorized → Redirect to login
- **Expired Token**: 401 Unauthorized → Redirect to login
- **Invalid Credentials**: 401 Unauthorized → Show error message

---

## Future Enhancements

1. **Streaming Responses**: Server-Sent Events (SSE) for real-time answer streaming
2. **Chunked File Upload**: Resumable uploads for very large files
3. **Advanced Chunking**: Semantic chunking (by topic, not just pages)
4. **Multi-Model Support**: Support for other LLMs (Claude, GPT-4)
5. **Query Caching**: Cache frequent queries
6. **Export Features**: Export chat history, documents
7. **Collaboration**: Share chats/documents with other users
8. **Advanced Search**: Full-text search + vector search hybrid

---

## Conclusion

This document provides a comprehensive overview of the DeepDocAI system architecture, design, and implementation. The system is designed to be:
- **Scalable**: Stateless API, can scale horizontally
- **Secure**: JWT authentication, multi-tenancy, input validation
- **Performant**: Vector indexes, batch processing, async operations
- **User-friendly**: ChatGPT-like UI, large context windows, internet search

For questions or clarifications, refer to the source code or contact the development team.

