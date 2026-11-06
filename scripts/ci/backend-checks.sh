#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
backend_dir="$repo_root/backend"
container="${POSTGRES_CONTAINER_ID:-}"
if [[ -z "$container" ]]; then
	echo "POSTGRES_CONTAINER_ID must identify the disposable PostgreSQL service." >&2
	exit 1
fi

databases=()
tokens=()

cleanup() {
	exit_code=$?
	for index in "${!databases[@]}"; do
		database="${databases[$index]}"
		token="${tokens[$index]}"
		comment="$(docker exec "$container" psql -U drift -d postgres -Atqc \
			"SELECT COALESCE(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '$database'" 2>/dev/null || true)"
		if [[ "$comment" == "$token" ]]; then
			docker exec "$container" dropdb -U drift --if-exists "$database" || exit_code=1
		else
			echo "Refusing to drop $database because its ownership marker changed." >&2
			exit_code=1
		fi
	done
	exit "$exit_code"
}
trap cleanup EXIT

for attempt in {1..30}; do
	if docker exec "$container" pg_isready -U drift -d drift_test >/dev/null 2>&1; then
		break
	fi
	if [[ "$attempt" == 30 ]]; then
		echo "PostgreSQL did not become ready within 30 seconds." >&2
		exit 1
	fi
	sleep 1
done

create_owned_database() {
	local database="$1"
	local token
	token="$(openssl rand -hex 16)"
	if [[ ! "$database" =~ ^drift_cdg(81(archive|visibility|filterapi|filterservice|identity)|80status|150_arrival|152_coverage|168_pagesort|203_deadline|206_comparison)_[0-9]{14}_[a-f0-9]{12}$ ]]; then
		echo "Generated database name failed the ownership guard: $database" >&2
		return 1
	fi
	if [[ "$(docker exec "$container" psql -U drift -d postgres -Atqc \
		"SELECT 1 FROM pg_database WHERE datname = '$database'")" == "1" ]]; then
		echo "Refusing to reuse existing database $database." >&2
		return 1
	fi
	docker exec "$container" createdb -U drift -O drift "$database"
	databases+=("$database")
	tokens+=("$token")
	docker exec "$container" psql -U drift -d postgres -v ON_ERROR_STOP=1 \
		-c "COMMENT ON DATABASE $database IS '$token'" >/dev/null
	created_token="$token"
}

configure_database() {
	local variable="$1"
	local prefix="$2"
	local credential_prefix="$3"
	local port="${4:-5432}"
	local database token url
	database="${prefix}_$(date -u +%Y%m%d%H%M%S)_$(openssl rand -hex 6)"
	create_owned_database "$database"
	token="$created_token"
	url="jdbc:postgresql://127.0.0.1:$port/$database"
	export "${variable}_JDBC_URL=$url"
	export "${variable}_OWNERSHIP_TOKEN=$token"
	export "${credential_prefix}_DB_USERNAME=drift"
	export "${credential_prefix}_DB_PASSWORD=drift"
}

# The integration tests with startup guards require a fresh, uniquely named database and
# matching ownership token. Keep the group's credentials and the URL variables expected
# by each test source aligned here.
configure_database DRIFT_CDG81_ARCHIVE drift_cdg81archive DRIFT_CDG81
configure_database DRIFT_CDG81_VISIBILITY drift_cdg81visibility DRIFT_CDG81
configure_database DRIFT_CDG81_FILTER_API drift_cdg81filterapi DRIFT_CDG81
configure_database DRIFT_CDG81_FILTER_SERVICE drift_cdg81filterservice DRIFT_CDG81
configure_database DRIFT_CDG81_IDENTITY drift_cdg81identity DRIFT_CDG81
configure_database DRIFT_CDG81_STATUS drift_cdg80status DRIFT_CDG81
configure_database DRIFT_CDG150 drift_cdg150_arrival DRIFT_CDG150
configure_database DRIFT_CDG152 drift_cdg152_coverage DRIFT_CDG152
configure_database DRIFT_CDG168 drift_cdg168_pagesort DRIFT_CDG168
configure_database DRIFT_CDG203 drift_cdg203_deadline DRIFT_CDG203
configure_database DRIFT_CDG206 drift_cdg206_comparison DRIFT_CDG206 55433

# Migration tests create their own marker-guarded databases through these disposable admin URLs.
export DRIFT_CDG53_ADMIN_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres
export DRIFT_CDG53_DB_USERNAME=drift
export DRIFT_CDG53_DB_PASSWORD=drift
export DRIFT_CDG289_ADMIN_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres
export DRIFT_CDG289_DB_USERNAME=drift
export DRIFT_CDG289_DB_PASSWORD=drift

cd "$backend_dir"
bash ./mvnw -B -ntp clean test
