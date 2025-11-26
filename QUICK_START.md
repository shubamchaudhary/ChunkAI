# Quick Start Guide - Database Setup

## Prerequisites Check

Before starting, make sure you have:
- ✅ Docker Desktop installed and running
- ✅ Java 17 or 21 installed
- ✅ Google Gemini API Key (optional for now, but needed for queries)

## Step-by-Step Setup

### Step 1: Start PostgreSQL Database

Open PowerShell in the project directory and run:

```powershell
docker-compose up -d
```

This will:
- Download the PostgreSQL image with pgvector extension
- Create a container named `examprep-postgres`
- Initialize the database with the schema from `init.sql`
- Expose PostgreSQL on port 5432

**Wait 10-15 seconds** for the database to initialize.

### Step 2: Verify Database is Running

```powershell
# Check if container is running
docker ps

# Check database logs
docker logs examprep-postgres
```

You should see messages about database initialization and "database system is ready to accept connections".

### Step 3: Verify Schema was Created

```powershell
# Connect to database and list tables
docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "\dt"
```

You should see tables:
- users
- documents
- document_chunks
- processing_jobs
- query_history

### Step 4: Set Environment Variables (Optional but Recommended)

Create a `.env` file in the project root:

```env
GEMINI_API_KEY=your-api-key-here
JWT_SECRET=your-secret-key-change-in-production-min-256-bits
```

Or set them in PowerShell:

```powershell
$env:GEMINI_API_KEY="your-api-key-here"
$env:JWT_SECRET="your-secret-key-here"
```

### Step 5: Run the Application

```powershell
.\gradlew :examprep-api:bootRun
```

The application should start on `http://localhost:8080`

### Step 6: Test the API

Open a new PowerShell window and test:

```powershell
# Register a user
curl -X POST http://localhost:8080/api/v1/auth/register `
  -H "Content-Type: application/json" `
  -d '{\"email\":\"test@example.com\",\"password\":\"password123\",\"fullName\":\"Test User\"}'
```

## Troubleshooting

### Database Connection Issues

**Problem**: `password authentication failed`

**Solution**:
```powershell
# Stop and remove everything
docker-compose down -v

# Start fresh
docker-compose up -d

# Wait 15 seconds, then check logs
docker logs examprep-postgres
```

**Problem**: Port 5432 already in use

**Solution**: 
- Check if another PostgreSQL is running: `netstat -an | findstr 5432`
- Stop the other PostgreSQL instance
- Or change the port in `docker-compose.yml` and `application.properties`

**Problem**: Container won't start

**Solution**:
```powershell
# Check Docker is running
docker ps

# Check logs for errors
docker logs examprep-postgres

# Try removing and recreating
docker-compose down -v
docker-compose up -d
```

### Application Won't Start

**Problem**: Missing Gemini API Key

**Solution**: The app will start but queries won't work. Set the API key:
```powershell
$env:GEMINI_API_KEY="your-key-here"
```

**Problem**: Schema validation errors

**Solution**: Make sure `init.sql` ran successfully:
```powershell
docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "\dt"
```

If tables are missing, run:
```powershell
docker exec -i examprep-postgres psql -U examprep -d examprep_db < init.sql
```

## Next Steps

Once the application is running:

1. **Test Authentication**:
   - Register a user
   - Login and get JWT token

2. **Upload a Document**:
   - Use the token to upload a PDF/PPT
   - Check document status

3. **Ask Questions**:
   - Wait for document processing to complete
   - Query your documents

## API Endpoints Summary

- `POST /api/v1/auth/register` - Register new user
- `POST /api/v1/auth/login` - Login
- `POST /api/v1/documents/upload` - Upload document
- `GET /api/v1/documents` - List documents
- `GET /api/v1/documents/{id}/status` - Check processing status
- `POST /api/v1/query` - Ask questions
- `GET /api/v1/user/profile` - Get user profile

All endpoints except auth require: `Authorization: Bearer <token>`

