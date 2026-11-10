#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
container="${POSTGRES_CONTAINER_ID:-}"
if [[ -z "$container" ]]; then
	echo "POSTGRES_CONTAINER_ID must identify the disposable PostgreSQL service." >&2
	exit 1
fi

database="drift_cdg358_$(date -u +%Y%m%d%H%M%S)_$(openssl rand -hex 6)"
token="$(openssl rand -hex 16)"
if [[ ! "$database" =~ ^drift_cdg358_[0-9]{14}_[a-f0-9]{12}$ ]]; then
	echo "Generated CDG-358 database name failed its ownership guard." >&2
	exit 1
fi
if [[ "$(docker exec "$container" psql -U drift -d postgres -Atqc \
	"SELECT 1 FROM pg_database WHERE datname = '$database'")" == "1" ]]; then
	echo "Refusing to reuse existing database $database." >&2
	exit 1
fi

docker exec "$container" createdb -U drift -O drift "$database"
docker exec "$container" psql -U drift -d postgres -v ON_ERROR_STOP=1 \
	-c "COMMENT ON DATABASE $database IS '$token'" >/dev/null

cleanup() {
	local exit_code=$?
	local comment
	comment="$(docker exec "$container" psql -U drift -d postgres -Atqc \
		"SELECT COALESCE(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '$database'" 2>/dev/null || true)"
	if [[ "$comment" == "$token" ]]; then
		docker exec "$container" dropdb -U drift --if-exists "$database" || exit_code=1
	else
		echo "Refusing to drop $database because its ownership marker changed." >&2
		exit_code=1
	fi
	exit "$exit_code"
}
trap cleanup EXIT

export DRIFT_CDG358_JDBC_URL="jdbc:postgresql://127.0.0.1:5432/$database"
export DRIFT_CDG358_OWNERSHIP_TOKEN="$token"
export DRIFT_CDG358_DB_USERNAME=drift
export DRIFT_CDG358_DB_PASSWORD=drift
cd "$repo_root/backend"
bash ./mvnw -B -ntp -Dtest=MaritimeChangeEventControllerIntegrationTests test
