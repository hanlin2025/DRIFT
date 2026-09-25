#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d postgres
for attempt in {1..30}; do
    if docker compose exec -T postgres pg_isready -U drift -d drift >/dev/null 2>&1; then
        break
    fi
    if [ "$attempt" -eq 30 ]; then
        echo "PostgreSQL did not become ready within 30 seconds" >&2
        exit 1
    fi
    sleep 1
done
docker compose exec -T postgres psql -U drift -d postgres -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'CREATE DATABASE drift_test OWNER drift'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'drift_test')\gexec
SQL
cd backend
bash ./mvnw test "$@"
