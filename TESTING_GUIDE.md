# Testing Guide - ExamPrep AI

## Setting GEMINI_API_KEY

You have **3 options** to set the `GEMINI_API_KEY`:

### Option 1: Environment Variable (Recommended)

**Windows PowerShell:**
```powershell
$env:GEMINI_API_KEY="your-api-key-here"
```

**Windows Command Prompt:**
```cmd
set GEMINI_API_KEY=your-api-key-here
```

**Linux/Mac:**
```bash
export GEMINI_API_KEY="your-api-key-here"
```

**To make it permanent** (Windows PowerShell):
```powershell
[System.Environment]::SetEnvironmentVariable('GEMINI_API_KEY', 'your-api-key-here', 'User')
```

### Option 2: Application Properties File

Edit `examprep-api/src/main/resources/application.properties`:

```properties
gemini.api-key=your-api-key-here-directly
```

**Note:** This is less secure as the key will be in your code. Use only for local development.

### Option 3: Application Local Properties (Recommended for Local Dev)

Edit `examprep-api/src/main/resources/application-local.properties`:

```properties
gemini.api-key=your-api-key-here
```

Then run with the `local` profile:
```powershell
.\gradlew :examprep-api:bootRun --args='--spring.profiles.active=local'
```

---

## How to Get Your Gemini API Key

1. Go to: https://aistudio.google.com/
2. Sign in with your Google account
3. Click "Get API Key" → "Create API Key"
4. Copy the key (starts with `AIza...`)

---

## Testing the API

### Prerequisites

1. ✅ Database is running: `docker ps` (should show `examprep-postgres`)
2. ✅ Application is running: `.\gradlew :examprep-api:bootRun`
3. ✅ API is accessible at: `http://localhost:8080`

### Step 1: Register a User

**PowerShell:**
```powershell
$body = @{
    email = "test@example.com"
    password = "password123"
    fullName = "Test User"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/register" `
    -Method POST `
    -ContentType "application/json" `
    -Body $body
```

**cURL (if installed):**
```bash
curl -X POST http://localhost:8080/api/v1/auth/register `
  -H "Content-Type: application/json" `
  -d '{\"email\":\"test@example.com\",\"password\":\"password123\",\"fullName\":\"Test User\"}'
```

**Expected Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "uuid-here",
    "email": "test@example.com",
    "fullName": "Test User"
  }
}
```

### Step 2: Login

**PowerShell:**
```powershell
$body = @{
    email = "test@example.com"
    password = "password123"
} | ConvertTo-Json

$response = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body $body

$token = $response.token
Write-Host "Token: $token"
```

**cURL:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" `
  -d '{\"email\":\"test@example.com\",\"password\":\"password123\"}'
```

**Save the token** from the response - you'll need it for authenticated requests.

### Step 3: Upload a Document

**PowerShell:**
```powershell
$token = "your-token-from-login-response"

# Upload a PDF file
$filePath = "C:\path\to\your\document.pdf"
$fileBytes = [System.IO.File]::ReadAllBytes($filePath)
$boundary = [System.Guid]::NewGuid().ToString()
$LF = "`r`n"

$bodyLines = (
    "--$boundary",
    "Content-Disposition: form-data; name=`"file`"; filename=`"document.pdf`"",
    "Content-Type: application/pdf",
    "",
    [System.Text.Encoding]::GetEncoding("iso-8859-1").GetString($fileBytes),
    "--$boundary--"
) -join $LF

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/documents/upload" `
    -Method POST `
    -Headers @{Authorization = "Bearer $token"} `
    -ContentType "multipart/form-data; boundary=$boundary" `
    -Body ([System.Text.Encoding]::GetEncoding("iso-8859-1").GetBytes($bodyLines))
```

**Using Postman or Insomnia:**
- Method: `POST`
- URL: `http://localhost:8080/api/v1/documents/upload`
- Headers: `Authorization: Bearer YOUR_TOKEN`
- Body: `form-data`
- Key: `file` (type: File)
- Value: Select your PDF/PPT file

**cURL:**
```bash
curl -X POST http://localhost:8080/api/v1/documents/upload `
  -H "Authorization: Bearer YOUR_TOKEN" `
  -F "file=@/path/to/your/document.pdf"
```

**Expected Response:**
```json
{
  "id": "document-uuid",
  "fileName": "document.pdf",
  "fileType": "pdf",
  "fileSizeBytes": 12345,
  "processingStatus": "PENDING",
  "createdAt": "2025-11-27T04:25:00Z"
}
```

**Note:** The document will be processed asynchronously. Check status with Step 4.

### Step 4: Check Document Status

**PowerShell:**
```powershell
$token = "your-token"
$documentId = "document-uuid-from-upload-response"

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/documents/$documentId/status" `
    -Method GET `
    -Headers @{Authorization = "Bearer $token"}
```

**Expected Response:**
```json
{
  "status": "COMPLETED",
  "progress": 100,
  "chunksProcessed": 10,
  "totalChunks": 10,
  "estimatedTimeRemaining": 0
}
```

### Step 5: List Your Documents

**PowerShell:**
```powershell
$token = "your-token"

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/documents" `
    -Method GET `
    -Headers @{Authorization = "Bearer $token"}
```

### Step 6: Ask a Question (Requires GEMINI_API_KEY)

**PowerShell:**
```powershell
$token = "your-token"
$body = @{
    question = "What is the main topic of this document?"
    marks = 5
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/query" `
    -Method POST `
    -Headers @{Authorization = "Bearer $token"} `
    -ContentType "application/json" `
    -Body $body
```

**cURL:**
```bash
curl -X POST http://localhost:8080/api/v1/query `
  -H "Authorization: Bearer YOUR_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{\"question\":\"What is the main topic?\",\"marks\":5}'
```

**Expected Response:**
```json
{
  "answer": "The main topic is...",
  "sources": [
    {
      "documentId": "uuid",
      "fileName": "document.pdf",
      "pageNumber": 1,
      "slideNumber": null,
      "excerpt": "relevant text excerpt..."
    }
  ],
  "metadata": {
    "retrievalTimeMs": 150,
    "generationTimeMs": 1200,
    "totalTimeMs": 1350,
    "chunksUsed": 3
  }
}
```

### Step 7: Get Query History

**PowerShell:**
```powershell
$token = "your-token"

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/query/history" `
    -Method GET `
    -Headers @{Authorization = "Bearer $token"}
```

### Step 8: Get User Profile

**PowerShell:**
```powershell
$token = "your-token"

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/user/profile" `
    -Method GET `
    -Headers @{Authorization = "Bearer $token"}
```

---

## Quick Test Script (PowerShell)

Save this as `test-api.ps1`:

```powershell
# Set your API key
$env:GEMINI_API_KEY = "your-api-key-here"

# Base URL
$baseUrl = "http://localhost:8080"

# 1. Register
Write-Host "1. Registering user..."
$registerBody = @{
    email = "test@example.com"
    password = "password123"
    fullName = "Test User"
} | ConvertTo-Json

$registerResponse = Invoke-RestMethod -Uri "$baseUrl/api/v1/auth/register" `
    -Method POST -ContentType "application/json" -Body $registerBody

$token = $registerResponse.token
Write-Host "Token: $token`n"

# 2. Login
Write-Host "2. Logging in..."
$loginBody = @{
    email = "test@example.com"
    password = "password123"
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod -Uri "$baseUrl/api/v1/auth/login" `
    -Method POST -ContentType "application/json" -Body $loginBody

$token = $loginResponse.token
Write-Host "Login successful! Token: $token`n"

# 3. Get profile
Write-Host "3. Getting profile..."
$profile = Invoke-RestMethod -Uri "$baseUrl/api/v1/user/profile" `
    -Method GET -Headers @{Authorization = "Bearer $token"}
Write-Host "Profile: $($profile | ConvertTo-Json)`n"

Write-Host "✅ Basic API tests completed!"
Write-Host "Next: Upload a document and ask questions!"
```

Run it:
```powershell
.\test-api.ps1
```

---

## Using Postman/Insomnia

1. **Import Collection:**
   - Create a new collection
   - Add requests for each endpoint
   - Set base URL: `http://localhost:8080`

2. **Set Authorization:**
   - Type: Bearer Token
   - Token: (get from login response)

3. **Test Flow:**
   - Register → Login → Upload Document → Query

---

## Troubleshooting

### "401 Unauthorized"
- Make sure you're including the `Authorization: Bearer TOKEN` header
- Token might have expired (default: 24 hours)

### "500 Internal Server Error" on Query
- Check if `GEMINI_API_KEY` is set correctly
- Check application logs for detailed error

### "Document processing failed"
- Check if file format is supported (PDF, PPT, PPTX, images)
- Check application logs for processing errors
- Verify Tesseract OCR is installed (for image files)

### "Connection refused"
- Make sure application is running: `.\gradlew :examprep-api:bootRun`
- Check if port 8080 is available

---

## API Endpoints Summary

| Method | Endpoint | Auth Required | Description |
|--------|----------|---------------|-------------|
| POST | `/api/v1/auth/register` | No | Register new user |
| POST | `/api/v1/auth/login` | No | Login user |
| POST | `/api/v1/documents/upload` | Yes | Upload document |
| GET | `/api/v1/documents` | Yes | List documents |
| GET | `/api/v1/documents/{id}` | Yes | Get document details |
| GET | `/api/v1/documents/{id}/status` | Yes | Get processing status |
| DELETE | `/api/v1/documents/{id}` | Yes | Delete document |
| POST | `/api/v1/query` | Yes | Ask question |
| GET | `/api/v1/query/history` | Yes | Get query history |
| GET | `/api/v1/user/profile` | Yes | Get user profile |
| GET | `/api/v1/user/storage` | Yes | Get storage stats |

---

## Next Steps

1. ✅ Set `GEMINI_API_KEY` environment variable
2. ✅ Test authentication endpoints
3. ✅ Upload a test document (PDF or PPT)
4. ✅ Wait for processing to complete
5. ✅ Ask questions about your document
6. ✅ Explore query history and user profile

Happy testing! 🚀

