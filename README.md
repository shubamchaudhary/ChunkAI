# Chunk AI

An AI-powered exam preparation system where students can upload PPTs/PDFs/handwritten notes and get exam-ready answers structured by marks.

## Features

- 📚 Upload 50-100+ PPTs/PDFs/handwritten notes in bulk
- 🤖 Ask questions and get AI-generated answers
- 📝 Exam-ready answers structured by marks (5 marks, 10 marks format)
- 📍 Source attribution (which slide/page the answer came from)
- 🔍 Vector similarity search using pgvector
- 🔐 JWT-based authentication

## Tech Stack

- **Backend**: Spring Boot 3.2 + Gradle (Multi-module)
- **Database**: PostgreSQL + pgvector
- **LLM**: Google Gemini 2.5 Flash
- **Embeddings**: Google text-embedding-004
- **OCR**: Tesseract
- **File Processing**: Apache POI, PDFBox
- **Frontend**: React + Vite (coming soon)

## Prerequisites

- Java 17 or 21 (LTS versions)
- Node.js 18+ (for frontend)
- Docker Desktop (for local PostgreSQL)
- Google Gemini API Key ([Get one here](https://aistudio.google.com/))

## Quick Start

### 1. Clone the repository

```bash
git clone <repository-url>
cd ChunkAI
```

### 2. Set up environment variables

Copy `.env.example` to `.env` and fill in your values:

```bash
cp .env.example .env
```

Edit `.env` and add your Gemini API key:
```
GEMINI_API_KEY=your-api-key-here
```

### 3. Start PostgreSQL with Docker

```bash
docker-compose up -d
```

This will:
- Start PostgreSQL with pgvector extension
- Create the database schema automatically
- Expose PostgreSQL on port 5432

### 4. Build and run the backend

```bash
# Build all modules
./gradlew build

# Run the application
./gradlew :examprep-api:bootRun
```

The API will be available at `http://localhost:8080`

### 5. Test the API

```bash
# Register a new user
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "fullName": "Test User"
  }'

# Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123"
  }'
```

## Project Structure

```
examprep-ai/
├── examprep-api/          # REST API module
├── examprep-core/         # Business logic & document processing
├── examprep-data/         # Data access layer (JPA entities & repositories)
├── examprep-llm/          # LLM integration (Gemini client & RAG)
└── examprep-common/       # Shared utilities & constants
```

## API Endpoints

### Authentication
- `POST /api/v1/auth/register` - Register new user
- `POST /api/v1/auth/login` - User login

### Documents
- `POST /api/v1/documents/upload` - Upload document
- `GET /api/v1/documents` - List user's documents
- `GET /api/v1/documents/{id}` - Get document details
- `DELETE /api/v1/documents/{id}` - Delete document

### Query
- `POST /api/v1/query` - Ask question and get AI answer

## Development

### Running Tests

```bash
./gradlew test
```

### Database Migrations

The schema is initialized automatically via `init.sql` when PostgreSQL starts. For production, consider using Flyway or Liquibase.

### Environment Variables

- `GEMINI_API_KEY` - Your Google Gemini API key (required)
- `JWT_SECRET` - Secret key for JWT tokens (change in production)
- Database connection settings (defaults to local Docker PostgreSQL)

## Deployment

### Railway Deployment

1. Create a Railway account and project
2. Add PostgreSQL service (with pgvector extension)
3. Deploy the Spring Boot application
4. Set environment variables in Railway dashboard

### Frontend (Coming Soon)

The React frontend will be deployed separately on Vercel or similar platform.

## License

MIT

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

