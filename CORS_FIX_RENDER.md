# Fix CORS 403 Error - Render Environment Variable

## Problem
Getting `403 Forbidden` on OPTIONS request (CORS preflight) from Vercel frontend to Render backend.

## Solution

### Step 1: Add Environment Variable in Render

1. Go to: https://dashboard.render.com
2. Select your backend service (`chunkai`)
3. Click on **Environment** tab
4. Click **Add Environment Variable**
5. Set:
   - **Key**: `CORS_ALLOWED_ORIGINS`
   - **Value**: `https://deepdocai.vercel.app,http://localhost:5173,http://localhost:3000`
6. Click **Save Changes**
7. Wait for auto-redeploy (check Logs tab)

### Step 2: Verify Deployment

After redeploy, check the logs for:
```
CORS allowed origins from environment: [https://deepdocai.vercel.app, ...]
```

### Step 3: Test Connection

1. Open your Vercel frontend: https://deepdocai.vercel.app
2. Try registering a user
3. Should work now! ✅

## If You Have Multiple Vercel Deployments

If you have preview deployments or custom domains, add them too:

```
https://deepdocai.vercel.app,https://deepdocai-git-main-yourname.vercel.app,http://localhost:5173,http://localhost:3000
```

## Environment Variable Format

- Separate multiple URLs with commas (no spaces)
- Always include `https://` or `http://`
- No trailing slashes
- Case-sensitive

## Troubleshooting

### Still getting 403?
1. Check Render logs for CORS configuration message
2. Verify the Vercel URL matches exactly (including `https://`)
3. Clear browser cache and try again
4. Check browser console for the exact error

### Not redeploying?
- Click "Manual Deploy" → "Clear build cache & deploy"

