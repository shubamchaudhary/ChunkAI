# PowerShell script to run v2.0 migration
# This script connects to PostgreSQL and runs the migration

Write-Host "Running DeepDocAI v2.0 Migration..." -ForegroundColor Green

# Read migration script content
$migrationScript = Get-Content -Path "migration_v2.0.sql" -Raw

# Connect to PostgreSQL and run migration
docker exec -i examprep-postgres psql -U examprep -d examprep_db <<EOF
$migrationScript
EOF

if ($LASTEXITCODE -eq 0) {
    Write-Host "Migration completed successfully!" -ForegroundColor Green
    Write-Host "Verifying tables..." -ForegroundColor Yellow
    
    # Verify tables exist
    docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "\dt api_key_usage"
    docker exec -it examprep-postgres psql -U examprep -d examprep_db -c "\dt query_cache"
} else {
    Write-Host "Migration failed. Please check the error above." -ForegroundColor Red
}

