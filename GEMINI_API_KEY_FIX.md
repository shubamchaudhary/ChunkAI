# Fix Gemini API 403 Forbidden Error

## Problem
You're getting `403 Forbidden` when trying to generate embeddings because the Gemini API key is not being loaded correctly.

## Solution Options

### Option 1: Set Environment Variable (Recommended)

**Windows PowerShell:**
```powershell
$env:GEMINI_API_KEY="AIzaSyAQS8WjZaf0nIoYwEYEKG9kQ4UQjg3AXZU"
```

**Then restart your application:**
```powershell
.\gradlew :examprep-api:bootRun
```

### Option 2: Run with Local Profile

Run the application with the `local` profile to use `application-local.properties`:

```powershell
.\gradlew :examprep-api:bootRun --args='--spring.profiles.active=local'
```

### Option 3: Set API Key in application.properties

Edit `examprep-api/src/main/resources/application.properties`:

```properties
gemini.api-key=AIzaSyAQS8WjZaf0nIoYwEYEKG9kQ4UQjg3AXZU
```

**Note:** This is less secure as the key will be in your code. Use only for local development.

---

## Verify API Key is Loaded

After restarting, check the logs. You should see:
```
Generating embedding using URL: ... (API key length: 39)
```

If you see "API key length: 0" or an error about API key not being set, the key isn't loading correctly.

---

## Common Issues

### Issue 1: API Key Invalid/Expired
- Go to https://aistudio.google.com/
- Check if your API key is still valid
- Create a new API key if needed

### Issue 2: API Key Doesn't Have Access
- Make sure your API key has access to embedding models
- Some API keys might be restricted to certain models

### Issue 3: Wrong Profile Active
- Check logs for: `No active profile set, falling back to 1 default profile: "default"`
- If you see this, use Option 2 above to activate the `local` profile

---

## Quick Fix (Copy-Paste)

**PowerShell:**
```powershell
# Set API key
$env:GEMINI_API_KEY="AIzaSyAQS8WjZaf0nIoYwEYEKG9kQ4UQjg3AXZU"

# Restart application (stop current one first with Ctrl+C)
.\gradlew :examprep-api:bootRun
```

This should fix the 403 error!

