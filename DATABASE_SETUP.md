# Database Setup Guide

## Issue: Password Authentication Failed

If you see `FATAL: password authentication failed for user "examprep"`, follow these steps:

## Step 1: Start PostgreSQL with Docker

```powershell
# Start the PostgreSQL container
docker-compose up -d

# Verify it's running
docker ps
```

You should see a container named `examprep-postgres` running.

## Step 2: Check Database Logs

```powershell
# Check if database initialized correctly
docker logs examprep-postgres
```

Look for any errors during initialization.

## Step 3: Verify Database Connection

```powershell
# Connect to the database
docker exec -it examprep-postgres psql -U examprep -d examprep_db
```

If this fails, the database might not be set up correctly.

## Step 4: Manual Database Setup (if needed)

If the container is running but the database isn't initialized:

```powershell
# Connect as postgres superuser
docker exec -it examprep-postgres psql -U postgres

# Then run these commands:
CREATE USER examprep WITH PASSWORD 'examprep_password';
CREATE DATABASE examprep_db OWNER examprep;
GRANT ALL PRIVILEGES ON DATABASE examprep_db TO examprep;
\q

# Connect to examprep_db and run init.sql
docker exec -i examprep-postgres psql -U examprep -d examprep_db < init.sql
```

## Step 5: Reset Docker Container (if still having issues)

```powershell
# Stop and remove containers/volumes
docker-compose down -v

# Start fresh
docker-compose up -d

# Wait a few seconds, then check logs
docker logs examprep-postgres
```

## Step 6: Verify Application Properties

Make sure `application.properties` has the correct credentials:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/examprep_db
spring.datasource.username=examprep
spring.datasource.password=examprep_password
```

## Common Issues

1. **Container not running**: Run `docker-compose up -d`
2. **Wrong password**: Check `docker-compose.yml` and `application.properties` match
3. **Database not initialized**: Run `init.sql` manually
4. **Port conflict**: Make sure port 5432 is not used by another PostgreSQL instance

## Quick Test

```powershell
# Test connection from host
docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "SELECT version();"
```

If this works, your database is ready!

