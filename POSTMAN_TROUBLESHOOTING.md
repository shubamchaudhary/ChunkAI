# Postman File Upload Troubleshooting

## ✅ Exact Postman Setup (Step-by-Step)

### Step 1: Create New Request
1. Click **New** → **HTTP Request**
2. Name it: "Upload Document"

### Step 2: Set Method and URL
- **Method**: Select `POST` from dropdown
- **URL**: `http://localhost:8080/api/v1/documents/upload`

### Step 3: Add Authorization Header
1. Go to **Headers** tab
2. Click **Add Header**
3. **Key**: `Authorization`
4. **Value**: `Bearer YOUR_TOKEN_HERE` (replace with actual token from login)
5. ✅ **IMPORTANT**: Make sure this header is NOT disabled (checkbox should be checked)

### Step 4: Configure Body (CRITICAL STEP)
1. Go to **Body** tab
2. **Select**: `form-data` radio button (NOT `raw`, NOT `x-www-form-urlencoded`)
3. You'll see a table with columns: Key | Value | Description
4. In the first row:
   - **Key**: Type exactly `file` (lowercase, no quotes)
   - **Value**: Click the dropdown on the right side of the Value field
   - **Select**: `File` from the dropdown (NOT `Text`)
   - **File Selector**: Click "Select Files" button that appears
   - Choose your PDF/PPT/image file

### Step 5: Verify Setup
Your Body tab should look like this:

```
┌─────────────────────────────────────────────┐
│ Body                                        │
│ ○ none  ● form-data  ○ x-www-form-urlencoded│
│                                             │
│ Key    │ Value        │ Description         │
│ ┌────┐ │ ┌──────────┐ │ ┌────────────────┐ │
│ │file│ │ │[Select..]│ │ │                │ │
│ └────┘ │ └──────────┘ │ └────────────────┘ │
│        │              │                     │
│        │ [Select Files]                    │
└─────────────────────────────────────────────┘
```

**Important checks:**
- ✅ `form-data` is selected (not raw)
- ✅ Key is exactly `file` (lowercase)
- ✅ Type dropdown shows `File` (not Text)
- ✅ A file is selected

### Step 6: Send Request
Click **Send** button

---

## 🔍 Debugging: Check What Postman is Sending

### Option 1: View Raw Request
1. In Postman, click **View** → **Show Postman Console** (or `Ctrl+Alt+C`)
2. Send the request
3. In the console, expand the request
4. Check the **Headers** section - you should see:
   ```
   Content-Type: multipart/form-data; boundary=----WebKitFormBoundary...
   ```
5. Check the **Body** section - you should see the file data

### Option 2: Use Postman's Code Generator
1. After setting up your request, click **Code** (bottom right)
2. Select **cURL** from dropdown
3. Copy the generated command
4. It should look like:
   ```bash
   curl --location 'http://localhost:8080/api/v1/documents/upload' \
   --header 'Authorization: Bearer YOUR_TOKEN' \
   --form 'file=@"/path/to/your/file.pdf"'
   ```
5. If it doesn't have `--form 'file=@...'`, your Postman setup is wrong

---

## ❌ Common Mistakes

### Mistake 1: Using "raw" Body Type
**Wrong:**
```
Body: raw
Content-Type: application/json
{
  "file": "base64-string"
}
```
**Fix**: Use `form-data` instead

### Mistake 2: Key Name is Wrong
**Wrong:**
- Key: `document` ❌
- Key: `upload` ❌
- Key: `File` ❌ (case-sensitive)
- Key: `"file"` ❌ (with quotes)

**Correct:**
- Key: `file` ✅ (exactly, lowercase, no quotes)

### Mistake 3: Type is "Text" Instead of "File"
**Wrong:**
```
Key: file
Type: Text  ❌
Value: /path/to/file.pdf
```

**Correct:**
```
Key: file
Type: File  ✅
Value: [Select Files button]
```

### Mistake 4: Content-Type Header Manually Set
**Wrong:**
```
Headers:
Content-Type: multipart/form-data  ❌
```

**Correct:**
- **Don't manually set Content-Type header**
- Postman will automatically set it when you use `form-data`
- If you manually set it, remove it!

### Mistake 5: Authorization Header Missing or Wrong Format
**Wrong:**
```
Authorization: YOUR_TOKEN  ❌
Authorization: BearerYOUR_TOKEN  ❌ (no space)
```

**Correct:**
```
Authorization: Bearer YOUR_TOKEN  ✅
```

---

## 🧪 Test with cURL (Alternative)

If Postman still doesn't work, test with cURL:

```bash
curl -X POST http://localhost:8080/api/v1/documents/upload \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -F "file=@/path/to/your/document.pdf"
```

**Windows PowerShell:**
```powershell
$token = "YOUR_TOKEN_HERE"
$filePath = "C:\path\to\your\document.pdf"

curl.exe -X POST http://localhost:8080/api/v1/documents/upload `
  -H "Authorization: Bearer $token" `
  -F "file=@$filePath"
```

If cURL works but Postman doesn't, the issue is with Postman setup.

---

## 📋 Postman Checklist

Before sending, verify:

- [ ] Method is `POST`
- [ ] URL is `http://localhost:8080/api/v1/documents/upload`
- [ ] Authorization header exists: `Bearer YOUR_TOKEN`
- [ ] Body type is `form-data` (not raw, not x-www-form-urlencoded)
- [ ] Key name is exactly `file` (lowercase, no quotes)
- [ ] Type dropdown shows `File` (not Text)
- [ ] A file is actually selected
- [ ] Content-Type header is NOT manually set (let Postman set it)
- [ ] No other headers that might interfere

---

## 🔧 Advanced: Enable Postman Console Logging

1. **View** → **Show Postman Console** (`Ctrl+Alt+C` or `Cmd+Alt+C`)
2. Send your request
3. In console, check:
   - **Request Headers**: Should have `Content-Type: multipart/form-data...`
   - **Request Body**: Should show file data (not empty)
   - **Response**: Check error message

---

## 💡 Still Not Working?

1. **Restart Postman**: Sometimes Postman caches old request settings
2. **Create Fresh Request**: Don't duplicate, create a brand new request
3. **Check File Size**: Make sure file is under 50MB
4. **Try Different File**: Test with a small PDF first (< 1MB)
5. **Check Application Logs**: Look for the debug logs we added:
   ```
   Upload request received. Content-Type: ...
   File received: name=..., size=...
   ```

---

## 📞 What to Share if Still Failing

If it still doesn't work, share:

1. **Postman Console Output** (request headers and body preview)
2. **Application Logs** (the debug logs we added)
3. **Screenshot** of Postman Body tab showing your setup
4. **cURL command** generated by Postman (Code → cURL)

---

## ✅ Expected Success Response

When it works, you should get:

```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "fileName": "document.pdf",
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

Good luck! 🚀

