#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
database="drift_cdg198_$(date +%s)_${RANDOM}_$$"
created=0

cleanup() {
	status=$?
	if [[ "$created" == 1 && "${KEEP_DB:-0}" != 1 ]]; then
		docker compose exec -T postgres dropdb -U drift --if-exists "$database" || true
	fi
	exit "$status"
}

trap cleanup EXIT
[[ "$database" =~ ^drift_cdg198_[0-9]+_[0-9]+_[0-9]+$ ]] || exit 1
docker compose up -d postgres
for attempt in {1..30}; do
	docker compose exec -T postgres pg_isready -U drift -d drift >/dev/null 2>&1 && break
	[[ "$attempt" == 30 ]] && exit 1
	sleep 1
done
docker compose exec -T postgres createdb -U drift "$database"
created=1
export DRIFT_TEST_DATABASE_URL="jdbc:postgresql://localhost:5432/$database"
export DRIFT_TEST_DATABASE_USERNAME=drift
export DRIFT_TEST_DATABASE_PASSWORD=drift
cd backend
bash ./mvnw -Dtest=ShipmentRiskAlertActivationConcurrencyIntegrationTests test
