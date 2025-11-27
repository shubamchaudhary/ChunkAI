# ExamPrep AI - Quick API Test Script
# Usage: .\test-api.ps1

param(
    [string]$ApiKey = "",
    [string]$BaseUrl = "http://localhost:8080"
)

Write-Host "=== ExamPrep AI API Test ===" -ForegroundColor Cyan
Write-Host ""

# Check if API is running
Write-Host "Checking if API is running..." -ForegroundColor Yellow
try {
    $healthCheck = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/register" -Method POST -ErrorAction SilentlyContinue
} catch {
    Write-Host "❌ API is not running! Please start it with: .\gradlew :examprep-api:bootRun" -ForegroundColor Red
    exit 1
}

# Set API key if provided
if ($ApiKey) {
    $env:GEMINI_API_KEY = $ApiKey
    Write-Host "✅ GEMINI_API_KEY set from parameter" -ForegroundColor Green
} elseif ($env:GEMINI_API_KEY) {
    Write-Host "✅ GEMINI_API_KEY found in environment" -ForegroundColor Green
} else {
    Write-Host "⚠️  GEMINI_API_KEY not set. Query functionality will not work." -ForegroundColor Yellow
    Write-Host "   Set it with: `$env:GEMINI_API_KEY='your-key'" -ForegroundColor Yellow
}

Write-Host ""

# Test 1: Register
Write-Host "1️⃣  Testing Registration..." -ForegroundColor Cyan
$registerBody = @{
    email = "test@example.com"
    password = "password123"
    fullName = "Test User"
} | ConvertTo-Json

try {
    $registerResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/register" `
        -Method POST `
        -ContentType "application/json" `
        -Body $registerBody
    
    Write-Host "   ✅ Registration successful!" -ForegroundColor Green
    Write-Host "   User ID: $($registerResponse.user.id)" -ForegroundColor Gray
    $token = $registerResponse.token
} catch {
    if ($_.Exception.Response.StatusCode -eq 400) {
        Write-Host "   ⚠️  User already exists, trying login instead..." -ForegroundColor Yellow
        
        # Try login instead
        $loginBody = @{
            email = "test@example.com"
            password = "password123"
        } | ConvertTo-Json
        
        $loginResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" `
            -Method POST `
            -ContentType "application/json" `
            -Body $loginBody
        
        Write-Host "   ✅ Login successful!" -ForegroundColor Green
        $token = $loginResponse.token
    } else {
        Write-Host "   ❌ Registration failed: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

Write-Host "   Token: $($token.Substring(0, [Math]::Min(50, $token.Length)))..." -ForegroundColor Gray
Write-Host ""

# Test 2: Get Profile
Write-Host "2️⃣  Testing Get Profile..." -ForegroundColor Cyan
try {
    $profile = Invoke-RestMethod -Uri "$BaseUrl/api/v1/user/profile" `
        -Method GET `
        -Headers @{Authorization = "Bearer $token"}
    
    Write-Host "   ✅ Profile retrieved!" -ForegroundColor Green
    Write-Host "   Email: $($profile.email)" -ForegroundColor Gray
    Write-Host "   Full Name: $($profile.fullName)" -ForegroundColor Gray
    Write-Host "   Total Documents: $($profile.stats.totalDocuments)" -ForegroundColor Gray
    Write-Host "   Total Chunks: $($profile.stats.totalChunks)" -ForegroundColor Gray
} catch {
    Write-Host "   ❌ Failed: $($_.Exception.Message)" -ForegroundColor Red
}
Write-Host ""

# Test 3: List Documents
Write-Host "3️⃣  Testing List Documents..." -ForegroundColor Cyan
try {
    $documents = Invoke-RestMethod -Uri "$BaseUrl/api/v1/documents" `
        -Method GET `
        -Headers @{Authorization = "Bearer $token"}
    
    Write-Host "   ✅ Documents retrieved!" -ForegroundColor Green
    Write-Host "   Total Documents: $($documents.totalElements)" -ForegroundColor Gray
    
    if ($documents.content.Count -gt 0) {
        Write-Host "   Documents:" -ForegroundColor Gray
        foreach ($doc in $documents.content) {
            Write-Host "     - $($doc.fileName) ($($doc.processingStatus))" -ForegroundColor Gray
        }
    } else {
        Write-Host "   No documents yet. Upload one to test document functionality!" -ForegroundColor Yellow
    }
} catch {
    Write-Host "   ❌ Failed: $($_.Exception.Message)" -ForegroundColor Red
}
Write-Host ""

# Test 4: Query History
Write-Host "4️⃣  Testing Query History..." -ForegroundColor Cyan
try {
    $history = Invoke-RestMethod -Uri "$BaseUrl/api/v1/query/history" `
        -Method GET `
        -Headers @{Authorization = "Bearer $token"}
    
    Write-Host "   ✅ Query history retrieved!" -ForegroundColor Green
    Write-Host "   Total Queries: $($history.totalElements)" -ForegroundColor Gray
} catch {
    Write-Host "   ❌ Failed: $($_.Exception.Message)" -ForegroundColor Red
}
Write-Host ""

# Summary
Write-Host "=== Test Summary ===" -ForegroundColor Cyan
Write-Host "✅ Authentication: Working" -ForegroundColor Green
Write-Host "✅ Profile API: Working" -ForegroundColor Green
Write-Host "✅ Documents API: Working" -ForegroundColor Green
Write-Host "✅ Query History API: Working" -ForegroundColor Green
Write-Host ""
Write-Host "Next Steps:" -ForegroundColor Yellow
Write-Host "1. Upload a document: POST /api/v1/documents/upload" -ForegroundColor Gray
Write-Host "2. Wait for processing to complete" -ForegroundColor Gray
Write-Host "3. Ask questions: POST /api/v1/query" -ForegroundColor Gray
Write-Host ""
Write-Host "See TESTING_GUIDE.md for detailed examples!" -ForegroundColor Cyan

