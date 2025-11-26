# ExamPrep AI - Setup Guide

## Prerequisites

1. **Java 17 or 21** (LTS versions)
   - Download from: https://adoptium.net/
   - Verify: `java -version`

2. **Docker Desktop**
   - Download from: https://www.docker.com/products/docker-desktop
   - Verify: `docker --version`

3. **Google Gemini API Key**
   - Go to: https://aistudio.google.com/
   - Sign in with Google account
   - Click "Get API Key" → "Create API Key"
   - Copy the key (starts with `AIza...`)

## Step-by-Step Setup

### 1. Clone and Navigate

```bash
cd ChunkAI
```

### 2. Create Environment File

Create a `.env` file in the root directory:

```bash
# Windows PowerShell
New-Item -Path .env -ItemType File

# Linux/Mac
touch .env
```

Add the following content:

```env
GEMINI_API_KEY=your-api-key-here
JWT_SECRET=your-secret-key-change-in-production-min-256-bits-please-use-a-long-random-string
```

**Important**: Replace `your-api-key-here` with your actual Gemini API key.

### 3. Start PostgreSQL Database

```bash
docker-compose up -d
```

This will:
- Download the pgvector PostgreSQL image
- Create a database named `examprep_db`
- Run the initialization SQL script
- Expose PostgreSQL on port 5432

Verify it's running:
```bash
docker ps
```

You should see a container named `examprep-postgres` running.

### 4. Verify Database Schema

Connect to the database to verify tables were created:

```bash
# Using Docker exec
docker exec -it examprep-postgres psql -U examprep -d examprep_db

# Then run:
\dt

# You should see tables: users, documents, document_chunks, processing_jobs, query_history
```

### 5. Build the Project

```bash
# Windows
gradlew.bat build

# Linux/Mac
chmod +x gradlew
./gradlew build
```

**Note**: If you don't have Gradle wrapper, install Gradle 8.5+ or use the wrapper script.

### 6. Set Environment Variables

**Windows PowerShell:**
```powershell
$env:GEMINI_API_KEY="your-api-key-here"
$env:JWT_SECRET="your-secret-key-here"
```

**Linux/Mac:**
```bash
export GEMINI_API_KEY="your-api-key-here"
export JWT_SECRET="your-secret-key-here"
```

Or add them to your `.env` file and load them using a tool like `dotenv-cli`.

### 7. Run the Application

```bash
# Windows
gradlew.bat :examprep-api:bootRun

# Linux/Mac
./gradlew :examprep-api:bootRun
```

The API should start on `http://localhost:8080`

### 8. Test the API

**Register a user:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"test@example.com\",\"password\":\"password123\",\"fullName\":\"Test User\"}"
```

**Login:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"test@example.com\",\"password\":\"password123\"}"
```

Save the `token` from the response.

**Upload a document (replace TOKEN with your token):**
```bash
curl -X POST http://localhost:8080/api/v1/documents/upload \
  -H "Authorization: Bearer TOKEN" \
  -F "file=@/path/to/your/file.pdf"
```

**Ask a question:**
```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"What is the main topic?\",\"marks\":5}"
```

## Troubleshooting

### Database Connection Issues

If you see connection errors:
1. Verify Docker is running: `docker ps`
2. Check PostgreSQL logs: `docker logs examprep-postgres`
3. Verify port 5432 is not in use: `netstat -an | findstr 5432` (Windows) or `lsof -i :5432` (Linux/Mac)

### Gemini API Errors

If you see API errors:
1. Verify your API key is correct
2. Check rate limits: https://ai.google.dev/pricing
3. Ensure the API key has proper permissions

### Build Errors

If Gradle build fails:
1. Clear Gradle cache: `./gradlew clean`
2. Update Gradle wrapper: `./gradlew wrapper --gradle-version 8.5`
3. Check Java version: `java -version` (should be 17 or 21)

### Port Already in Use

If port 8080 is in use:
1. Change port in `examprep-api/src/main/resources/application.properties`
2. Or stop the process using port 8080

## Next Steps

- [ ] Set up the React frontend (coming soon)
- [ ] Configure file storage (S3, local filesystem)
- [ ] Set up async document processing worker
- [ ] Deploy to Railway/Vercel

## Support

For issues or questions, please open an issue on GitHub.

