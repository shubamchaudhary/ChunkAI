# Vercel Frontend Deployment Guide

This guide will walk you through deploying the ChunkAI frontend to Vercel.

## Prerequisites

- A Vercel account (sign up at [vercel.com](https://vercel.com))
- Backend API deployed at `https://chunkai.onrender.com`
- Git repository with your code

## Step 1: Prepare Your Frontend

The frontend has been configured to use environment variables for the API base URL. The following files have been set up:

- ✅ `examprep-frontend/src/services/api.js` - Updated to use `VITE_API_BASE_URL`
- ✅ `examprep-frontend/vercel.json` - Vercel configuration file
- ✅ Backend CORS configuration - Updated to accept environment variable for allowed origins

## Step 2: Update Backend CORS Configuration

Before deploying, you need to update your backend CORS settings on Render:

1. Go to your Render dashboard: https://dashboard.render.com
2. Select your backend service
3. Go to **Environment** tab
4. Add a new environment variable:
   - **Key**: `CORS_ALLOWED_ORIGINS`
   - **Value**: `http://localhost:5173,http://localhost:3000,https://your-app-name.vercel.app`
     - Replace `your-app-name` with your actual Vercel app name
     - You can add multiple origins separated by commas
     - After deployment, you'll also add your production Vercel URL here

5. Save and redeploy your backend service

## Step 3: Deploy to Vercel

### Option A: Deploy via Vercel Dashboard (Recommended for first deployment)

1. **Import your project:**
   - Go to [vercel.com/dashboard](https://vercel.com/dashboard)
   - Click **Add New** → **Project**
   - Import your Git repository (GitHub, GitLab, or Bitbucket)

2. **Configure the project:**
   - **Root Directory**: Set to `examprep-frontend`
   - **Framework Preset**: Vite (should auto-detect)
   - **Build Command**: `npm run build` (should auto-detect)
   - **Output Directory**: `dist` (should auto-detect)
   - **Install Command**: `npm install` (should auto-detect)

3. **Set Environment Variables:**
   - Click **Environment Variables**
   - Add the following:
     - **Key**: `VITE_API_BASE_URL`
     - **Value**: `https://chunkai.onrender.com/api/v1`
     - **Environment**: Production, Preview, Development (select all)

4. **Deploy:**
   - Click **Deploy**
   - Wait for the build to complete
   - Your app will be live at `https://your-app-name.vercel.app`

### Option B: Deploy via Vercel CLI

1. **Install Vercel CLI:**
   ```bash
   npm install -g vercel
   ```

2. **Navigate to frontend directory:**
   ```bash
   cd examprep-frontend
   ```

3. **Login to Vercel:**
   ```bash
   vercel login
   ```

4. **Deploy:**
   ```bash
   vercel
   ```
   - Follow the prompts
   - When asked for environment variables, add:
     - `VITE_API_BASE_URL` = `https://chunkai.onrender.com/api/v1`

5. **For production deployment:**
   ```bash
   vercel --prod
   ```

## Step 4: Update Backend CORS with Production URL

After your first deployment, you'll get a Vercel URL (e.g., `https://chunkai-frontend.vercel.app`):

1. Go back to your Render dashboard
2. Update the `CORS_ALLOWED_ORIGINS` environment variable to include your Vercel URL:
   ```
   http://localhost:5173,http://localhost:3000,https://chunkai-frontend.vercel.app
   ```
3. Redeploy your backend service

## Step 5: Verify Deployment

1. **Test the frontend:**
   - Visit your Vercel URL
   - Try logging in or registering
   - Test document upload
   - Test query functionality

2. **Check browser console:**
   - Open browser DevTools (F12)
   - Check for any CORS errors
   - Verify API calls are going to `https://chunkai.onrender.com/api/v1`

3. **Test API connectivity:**
   - The frontend should successfully connect to your backend
   - Authentication should work
   - All API endpoints should be accessible

## Step 6: Custom Domain (Optional)

If you want to use a custom domain:

1. Go to your Vercel project settings
2. Navigate to **Domains**
3. Add your custom domain
4. Follow DNS configuration instructions
5. Update `CORS_ALLOWED_ORIGINS` in your backend to include the custom domain

## Environment Variables Reference

### Frontend (Vercel)
- `VITE_API_BASE_URL`: Backend API base URL
  - Production: `https://chunkai.onrender.com/api/v1`
  - Local: `http://localhost:8080/api/v1`

### Backend (Render)
- `CORS_ALLOWED_ORIGINS`: Comma-separated list of allowed origins
  - Example: `http://localhost:5173,http://localhost:3000,https://your-app.vercel.app`

## Troubleshooting

### CORS Errors
- **Symptom**: Browser console shows CORS errors
- **Solution**: 
  - Verify `CORS_ALLOWED_ORIGINS` includes your Vercel URL
  - Ensure backend has been redeployed after updating CORS
  - Check that the URL matches exactly (including `https://`)

### API Connection Errors
- **Symptom**: Frontend can't connect to backend
- **Solution**:
  - Verify `VITE_API_BASE_URL` is set correctly in Vercel
  - Check that backend is running and accessible
  - Test backend URL directly in browser: `https://chunkai.onrender.com/api/v1/auth/register`

### Build Failures
- **Symptom**: Vercel build fails
- **Solution**:
  - Check build logs in Vercel dashboard
  - Ensure `package.json` has correct build scripts
  - Verify all dependencies are listed in `package.json`

### 404 Errors on Routes
- **Symptom**: Direct URL access or refresh shows 404
- **Solution**: 
  - The `vercel.json` file includes rewrite rules for SPA routing
  - Verify `vercel.json` is in the `examprep-frontend` directory

## Continuous Deployment

Vercel automatically deploys when you push to your Git repository:

- **Production**: Deploys from your main/master branch
- **Preview**: Deploys from other branches and pull requests

Each deployment gets a unique preview URL, which is great for testing before merging.

## Local Development

For local development, create a `.env` file in `examprep-frontend/`:

```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

This file should be in `.gitignore` and not committed to the repository.

## Next Steps

- Set up custom domain (if needed)
- Configure preview deployments for testing
- Set up monitoring and analytics
- Configure environment-specific variables for staging/production

## Support

If you encounter issues:
1. Check Vercel deployment logs
2. Check browser console for errors
3. Verify backend is accessible
4. Review CORS configuration
5. Check environment variables are set correctly

