# Testing Render PostgreSQL Connection

## ✅ Database Status
The database is **accepting connections** at:
- Host: `dpg-d4lka3ogjchc73apf3i0-a.singapore-postgres.render.com`
- Port: `5432`

## ⚠️ IMPORTANT: Verify Your Actual Credentials

**Based on your Render dashboard, your password appears to be:** `DeepDockPass@2025$`

**Username requirements:** Must be lowercase letters, numbers, and underscores only (regex: `[a-z_][a-z0-9_]*`)

### Check Your Actual Username:
1. Go to Render Dashboard → PostgreSQL Database
2. Look at "Internal Database URL" - it will show: `postgresql://USERNAME:PASSWORD@...`
3. The username is between `//` and `:`
4. Common usernames: `examprep`, `examprep_user`, or the default Render username

## IntelliJ Connection Settings

### Method 1: Using URL (Recommended)

1. In IntelliJ Database Tool Window:
   - Click `+` → `Data Source` → `PostgreSQL`

2. In the **URL** field, enter:
   ```
   jdbc:postgresql://dpg-d4lka3ogjchc73apf3i0-a.singapore-postgres.render.com:5432/examprep_db
   ```

3. Set these fields:
   - **User**: `[YOUR_ACTUAL_USERNAME]` (check Render dashboard - must be lowercase with underscores only)
   - **Password**: `DeepDockPass@2025$` (or the actual password from Render)

4. Click **Test Connection**

### Method 2: Using Host/Port (Alternative)

1. In IntelliJ Database Tool Window:
   - Click `+` → `Data Source` → `PostgreSQL`

2. Set these fields:
   - **Host**: `dpg-d4lka3ogjchc73apf3i0-a.singapore-postgres.render.com`
   - **Port**: `5432`
   - **Database**: `examprep_db`
   - **User**: `examprep`
   - **Password**: `u7E1Pyd05BvxdljB0lattjQQAuSabZAS`

3. **Important**: Clear/leave the **URL** field empty when using Host/Port

4. Click **Test Connection**

## Troubleshooting

### If connection times out:

1. **Wake up the database first:**
   - Go to Render Dashboard → Your PostgreSQL Database
   - Wait for it to show "Available" (not "Sleeping")
   - This may take 30-60 seconds

2. **Check firewall/VPN:**
   - Temporarily disable VPN if you're using one
   - Check if your firewall is blocking port 5432

3. **Try from different network:**
   - Some networks block database connections
   - Try from mobile hotspot to test

### If authentication fails (FATAL: password authentication failed):

**This means the password is incorrect or the user doesn't exist.**

1. **Verify credentials from Render Dashboard:**
   - Go to Render Dashboard → Your PostgreSQL Database
   - Click on "Connections" or "Info" tab
   - Copy the **exact** password shown (click "Show" if hidden)
   - Make sure there are no extra spaces before/after

2. **Check the Internal Database URL:**
   - In Render, look for "Internal Database URL"
   - It should look like: `postgresql://examprep:PASSWORD@dpg-...`
   - Extract the password from there (between `:` and `@`)

3. **Try resetting the password in Render:**
   - Go to Render Dashboard → PostgreSQL Database
   - Look for "Reset Password" or "Change Password" option
   - Set a new password (avoid special characters that might cause issues)
   - Use the new password in IntelliJ

4. **Verify username:**
   - Should be exactly: `examprep` (case-sensitive)
   - Check in Render dashboard what the actual username is

5. **If password has special characters:**
   - Try URL-encoding the password
   - Or reset to a simpler password without special chars

### Connection String Format

If you want to use the full connection string with credentials:
```
jdbc:postgresql://examprep:u7E1Pyd05BvxdljB0lattjQQAuSabZAS@dpg-d4lka3ogjchc73apf3i0-a.singapore-postgres.render.com:5432/examprep_db
```

But it's better to keep credentials separate in IntelliJ.

## Test Connection via Command Line (if psql is installed)

```bash
PGPASSWORD=u7E1Pyd05BvxdljB0lattjQQAuSabZAS psql -h dpg-d4lka3ogjchc73apf3i0-a.singapore-postgres.render.com -p 5432 -U examprep -d examprep_db -c "SELECT version();"
```

## Verify Connection Works

Once connected in IntelliJ, run this query to verify:
```sql
SELECT version();
SELECT current_database();
SELECT current_user;
```

Expected results:
- PostgreSQL version info
- Database: `examprep_db`
- User: `examprep`

