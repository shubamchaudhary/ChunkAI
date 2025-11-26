# How Docker Compose Sets Up PostgreSQL Automatically

## The Magic Explained

When you run `docker-compose up -d`, here's what happens automatically:

### Step 1: Docker Downloads PostgreSQL Image
```
docker-compose.yml says: "Use pgvector/pgvector:pg16"
↓
Docker downloads the image (if not already downloaded)
↓
You now have PostgreSQL 16 + pgvector extension ready!
```

### Step 2: Docker Creates a Container
```
Docker reads docker-compose.yml
↓
Creates a container named "examprep-postgres"
↓
Configures it with environment variables
```

### Step 3: PostgreSQL Container Starts
```
Container starts PostgreSQL server
↓
Reads environment variables:
  - POSTGRES_DB=examprep_db → Creates database
  - POSTGRES_USER=examprep → Creates user
  - POSTGRES_PASSWORD=examprep_password → Sets password
```

### Step 4: Automatic SQL Script Execution
```
PostgreSQL looks in /docker-entrypoint-initdb.d/
↓
Finds init.sql (mounted from ./init.sql)
↓
Runs ALL .sql files in that directory automatically
↓
Your tables, indexes, functions are created!
```

### Step 5: Database is Ready!
```
PostgreSQL is running
Database "examprep_db" exists
User "examprep" exists with password
All tables are created
Ready to accept connections!
```

## Why This Works

The PostgreSQL Docker image has a special feature:
- When the container starts **for the first time**
- It checks `/docker-entrypoint-initdb.d/` directory
- Runs any `.sql` or `.sh` files found there
- This only happens once (on first initialization)

## What You DON'T Need to Do

❌ Install PostgreSQL manually  
❌ Configure PostgreSQL settings  
❌ Create database manually  
❌ Create user manually  
❌ Run SQL scripts manually  
❌ Set up pgvector extension manually  

## What Docker DOES For You

✅ Downloads PostgreSQL  
✅ Creates container  
✅ Creates database  
✅ Creates user  
✅ Sets password  
✅ Runs init.sql automatically  
✅ Sets up pgvector extension  
✅ Exposes port 5432  
✅ Persists data in volumes  

## The Complete Flow

```
You run: docker-compose up -d
    ↓
Docker reads docker-compose.yml
    ↓
Downloads image (if needed)
    ↓
Creates container
    ↓
Starts PostgreSQL
    ↓
PostgreSQL reads environment variables
    ↓
Creates database and user
    ↓
Finds init.sql in /docker-entrypoint-initdb.d/
    ↓
Executes init.sql automatically
    ↓
Database is ready! 🎉
```

## Verify It Worked

After running `docker-compose up -d`, check:

```powershell
# 1. Container is running
docker ps

# 2. Database exists
docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "\l"

# 3. Tables exist
docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "\dt"

# 4. Extensions installed
docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "\dx"
```

You should see:
- `vector` extension
- `uuid-ossp` extension  
- All your tables (users, documents, document_chunks, etc.)

## Important Notes

1. **First time only**: The init.sql runs only when the database is first created
2. **Data persists**: Your data stays in Docker volumes even if you stop the container
3. **Reset everything**: `docker-compose down -v` removes volumes and you'll start fresh
4. **No manual setup**: Everything is automated!

## That's It!

Just run `docker-compose up -d` and everything is set up automatically. No manual PostgreSQL installation, no manual database creation, no manual SQL execution. Docker handles it all! 🚀

