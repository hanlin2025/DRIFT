#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
database="drift_cdg289_$(date -u +%Y%m%d%H%M%S)_$(tr -d '-' </proc/sys/kernel/random/uuid | cut -c1-12)"
ownership_token="$(tr -d '-' </proc/sys/kernel/random/uuid | cut -c1-32)"
created=0

cleanup() {
	status=$?
	if [[ "$created" == 1 ]]; then
		comment="$(docker exec drift-postgres psql -U drift -d postgres -Atqc "SELECT COALESCE(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '$database'" 2>/dev/null || true)"
		if [[ "$comment" == "$ownership_token" ]]; then
			docker exec drift-postgres dropdb -U drift --if-exists "$database" || status=1
		else
			echo "Refusing to drop $database because its ownership marker changed." >&2
			status=1
		fi
	fi
	exit "$status"
}
trap cleanup EXIT

[[ "$database" =~ ^drift_cdg289_[0-9]{14}_[a-f0-9]{12}$ ]] || exit 1
if docker inspect drift-postgres >/dev/null 2>&1; then
	if [[ "$(docker inspect --format '{{.State.Status}}' drift-postgres)" != running ]]; then
		docker start drift-postgres >/dev/null
	fi
else
	docker compose up -d postgres
fi
for attempt in {1..30}; do
	if docker exec drift-postgres pg_isready -U drift -d drift >/dev/null 2>&1; then break; fi
	[[ "$attempt" == 30 ]] && { echo "PostgreSQL did not become ready within 30 seconds" >&2; exit 1; }
	sleep 1
done
if docker exec drift-postgres psql -U drift -d postgres -Atqc "SELECT 1 FROM pg_database WHERE datname = '$database'" | rg -q '^1$'; then
	echo "Refusing to reuse existing database $database." >&2
	exit 1
fi
docker exec drift-postgres createdb -U drift -O drift "$database"
created=1
docker exec drift-postgres psql -U drift -d postgres -v ON_ERROR_STOP=1 \
	-c "COMMENT ON DATABASE $database IS '$ownership_token'"

export DRIFT_CDG289_JDBC_URL="jdbc:postgresql://127.0.0.1:5432/$database"
export DRIFT_CDG289_DB_USERNAME=drift
export DRIFT_CDG289_DB_PASSWORD=drift
export DRIFT_CDG289_OWNERSHIP_TOKEN="$ownership_token"
cd backend
bash ./mvnw -o -Dtest=MaritimeAiScenarioAssessmentPersistenceIntegrationTests,ScenarioMaritimeAiAssessmentServiceTest,MaritimeAiScenarioTransientTest test
