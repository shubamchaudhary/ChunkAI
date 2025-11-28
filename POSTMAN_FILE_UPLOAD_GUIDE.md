# Postman File Upload Guide

## ✅ How to Upload Files in Postman

### Step-by-Step Instructions

1. **Set Request Method**: `POST`
2. **Set URL**: `http://localhost:8080/api/v1/documents/upload`
3. **Add Authorization Header**:
   - Go to **Headers** tab
   - Add: `Authorization: Bearer YOUR_TOKEN_HERE`
   - (Get token from login endpoint first)

4. **Set Body Type**:
   - Go to **Body** tab
   - Select **form-data** (NOT raw, NOT x-www-form-urlencoded)

5. **Add File Field**:
   - In the form-data section, you'll see key-value pairs
   - Click on the first key field
   - **Key name**: Type `file` (exactly, lowercase)
   - **Type**: Click the dropdown next to the key and select **File** (not Text)
   - **Value**: Click "Select Files" and choose your PDF/PPT/image file

6. **Send Request**

---

## 📸 Visual Guide

```
┌─────────────────────────────────────────┐
│ POST http://localhost:8080/api/v1/...  │
├─────────────────────────────────────────┤
│ Headers                                  │
│ ┌─────────────────────────────────────┐ │
│ │ Authorization: Bearer eyJhbGci...   │ │
│ └─────────────────────────────────────┘ │
├─────────────────────────────────────────┤
│ Body                                     │
│ ○ none  ○ form-data  ○ x-www-form...    │
│                                         │
│ Key          │ Value  │ Type            │
│ ┌──────────┐ │ ┌────┐ │ ┌───────────┐ │
│ │ file     │ │ │... │ │ │ File ▼    │ │
│ └──────────┘ │ └────┘ │ └───────────┘ │
│              │        │               │
│              │ [Select Files]        │
└─────────────────────────────────────────┘
```

---

## ⚠️ Common Mistakes

### ❌ Wrong: Using "raw" body with JSON
```json
{
  "file": "base64-encoded-string"
}
```
**This won't work!** Use `form-data` instead.

### ❌ Wrong: Key name is not "file"
- Key: `document` ❌
- Key: `upload` ❌
- Key: `file` ✅ (correct!)

### ❌ Wrong: Type is "Text" instead of "File"
- Type: Text ❌
- Type: File ✅ (correct!)

### ❌ Wrong: Using x-www-form-urlencoded
- This is for text data, not files
- Use `form-data` instead

---

## ✅ Correct Postman Setup

### Request Configuration:
```
Method: POST
URL: http://localhost:8080/api/v1/documents/upload
```

### Headers:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### Body (form-data):
| Key | Type | Value |
|-----|------|-------|
| `file` | **File** | [Select your PDF/PPT/image file] |

---

## 🧪 Complete Testing Flow

### 1. Register User
```
POST http://localhost:8080/api/v1/auth/register
Content-Type: application/json

{
  "email": "test@example.com",
  "password": "password123",
  "fullName": "Test User"
}
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "test@example.com",
    "fullName": "Test User"
  }
}
```

**Copy the `token` value!**

---

### 2. Upload Document

**Method**: `POST`  
**URL**: `http://localhost:8080/api/v1/documents/upload`

**Headers**:
```
Authorization: Bearer YOUR_TOKEN_HERE
```

**Body**:
- Type: `form-data`
- Key: `file` (Type: **File**)
- Value: Select your file (PDF, PPT, PPTX, or image)

**Expected Response**:
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "fileName": "sample.pdf",
  "fileType": "pdf",
  "fileSizeBytes": 245678,
  "totalPages": 0,
  "totalChunks": 0,
  "processingStatus": "PENDING",
  "errorMessage": null,
  "createdAt": "2025-01-27T10:10:00Z",
  "processingCompletedAt": null
}
```

---

### 3. Check Processing Status

**Method**: `GET`  
**URL**: `http://localhost:8080/api/v1/documents/{documentId}/status`  
**Replace `{documentId}` with the `id` from upload response**

**Headers**:
```
Authorization: Bearer YOUR_TOKEN_HERE
```

**Expected Response** (Processing):
```json
{
  "status": "PROCESSING",
  "progress": 45,
  "chunksProcessed": 9,
  "totalChunks": 20,
  "estimatedTimeRemaining": 15
}
```

**Expected Response** (Completed):
```json
{
  "status": "COMPLETED",
  "progress": 100,
  "chunksProcessed": 20,
  "totalChunks": 20,
  "estimatedTimeRemaining": 0
}
```

---

## 🔧 Troubleshooting

### Error: "Required part 'file' is not present"

**Causes:**
1. ❌ Body type is not `form-data`
2. ❌ Key name is not exactly `file` (case-sensitive)
3. ❌ Type is `Text` instead of `File`
4. ❌ No file selected

**Solution:**
- Go to **Body** tab
- Select **form-data**
- Set key to `file` (lowercase)
- Change type dropdown to **File**
- Click "Select Files" and choose a file

---

### Error: "401 Unauthorized"

**Cause**: Missing or invalid token

**Solution:**
- Login again to get a fresh token
- Add `Authorization: Bearer YOUR_TOKEN` header

---

### Error: "File type not supported"

**Cause**: File format not supported

**Supported formats:**
- PDF (`.pdf`)
- PowerPoint (`.ppt`, `.pptx`)
- Images (`.jpg`, `.jpeg`, `.png`, `.gif`, `.bmp`, `.tiff`)

---

## 📝 Quick Checklist

Before sending the upload request:

- [ ] Method is `POST`
- [ ] URL is correct: `http://localhost:8080/api/v1/documents/upload`
- [ ] Authorization header is set: `Bearer YOUR_TOKEN`
- [ ] Body type is `form-data` (not raw, not x-www-form-urlencoded)
- [ ] Key name is exactly `file` (lowercase)
- [ ] Type dropdown shows **File** (not Text)
- [ ] A file is selected

---

## 🎯 Example: Upload a PDF

1. **Login** → Get token
2. **Create new request**:
   - Method: `POST`
   - URL: `http://localhost:8080/api/v1/documents/upload`
3. **Headers tab**:
   - Add: `Authorization: Bearer eyJhbGci...`
4. **Body tab**:
   - Select: `form-data`
   - Key: `file`
   - Type: `File` (from dropdown)
   - Value: Click "Select Files" → Choose `document.pdf`
5. **Send**

---

## 💡 Pro Tips

1. **Save token as variable**: In Postman, save your token as a collection variable so you don't have to copy-paste it every time.

2. **Use Collection Authorization**: Set Bearer token at collection level so all requests inherit it.

3. **Test with small files first**: Start with a small PDF (< 1MB) to test the flow.

4. **Check processing status**: After upload, poll the status endpoint every few seconds until it's `COMPLETED`.

---

## 📚 Related Endpoints

- `POST /api/v1/documents/upload` - Upload single file
- `POST /api/v1/documents/upload/bulk` - Upload multiple files
- `GET /api/v1/documents/{id}/status` - Check processing status
- `GET /api/v1/documents` - List all documents

---

## 📦 Bulk Upload (Multiple Files)

### For uploading 15 files (or any multiple files):

1. **Set Request Method**: `POST`
2. **Set URL**: `http://localhost:8080/api/v1/documents/upload/bulk` ⚠️ **Note: `/bulk` at the end**
3. **Add Authorization Header**:
   - Go to **Headers** tab
   - Add: `Authorization: Bearer YOUR_TOKEN_HERE`

4. **Set Body Type**:
   - Go to **Body** tab
   - Select **form-data** (NOT raw, NOT x-www-form-urlencoded)

5. **Add Files Field**:
   - In the form-data section, add a key-value pair
   - **Key name**: Type `files` (plural, lowercase) ⚠️ **Important: `files` not `file`**
   - **Type**: Click the dropdown next to the key and select **File** (not Text)
   - **Value**: Click "Select Files" and choose **multiple files** (hold Ctrl/Cmd to select multiple)

6. **Send Request**

### Postman Bulk Upload Setup:

```
┌─────────────────────────────────────────┐
│ POST http://localhost:8080/api/v1/...  │
│                    /upload/bulk         │
├─────────────────────────────────────────┤
│ Headers                                  │
│ ┌─────────────────────────────────────┐ │
│ │ Authorization: Bearer eyJhbGci...   │ │
│ └─────────────────────────────────────┘ │
├─────────────────────────────────────────┤
│ Body                                     │
│ ○ none  ○ form-data  ○ x-www-form...    │
│                                         │
│ Key          │ Value  │ Type            │
│ ┌──────────┐ │ ┌────┐ │ ┌───────────┐ │
│ │ files    │ │ │... │ │ │ File ▼    │ │
│ └──────────┘ │ └────┘ │ └───────────┘ │
│              │        │               │
│              │ [Select Files]        │
│              │ (Select multiple)     │
└─────────────────────────────────────────┘
```

### ⚠️ Key Differences: Single vs Bulk Upload

| Feature | Single Upload | Bulk Upload |
|---------|--------------|-------------|
| **URL** | `/api/v1/documents/upload` | `/api/v1/documents/upload/bulk` |
| **Key Name** | `file` (singular) | `files` (plural) |
| **Files** | 1 file | Up to 20 files |
| **Response** | Single `DocumentResponse` | `BulkUploadResponse` with arrays |

### 📋 Bulk Upload Response Example:

```json
{
  "uploads": [
    {
      "id": "uuid-1",
      "fileName": "document1.pdf",
      "processingStatus": "PENDING"
    },
    {
      "id": "uuid-2",
      "fileName": "document2.pdf",
      "processingStatus": "PENDING"
    }
  ],
  "totalQueued": 12,
  "duplicates": [
    {
      "fileName": "duplicate.pdf",
      "fileSizeBytes": 12345,
      "existingDocumentId": "uuid-existing",
      "existingDocumentCreatedAt": "2025-11-27T..."
    }
  ],
  "errors": [],
  "totalFiles": 15,
  "successfulUploads": 12,
  "duplicateCount": 3,
  "errorCount": 0,
  "message": "Successfully uploaded 12 file(s). 3 duplicate file(s) skipped."
}
```

### ✅ Quick Checklist for Bulk Upload:

- [ ] URL ends with `/bulk`: `http://localhost:8080/api/v1/documents/upload/bulk`
- [ ] Key name is `files` (plural, not `file`)
- [ ] Type is **File** (not Text)
- [ ] Multiple files selected (hold Ctrl/Cmd)
- [ ] Authorization header is set
- [ ] Body type is `form-data`

---

Happy testing! 🚀

