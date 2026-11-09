#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
backend_dir="$repo_root/backend"
cd "$backend_dir"

container="$(printenv POSTGRES_CONTAINER_ID || true)"
if [[ -z "$container" ]]; then
  echo "POSTGRES_CONTAINER_ID must identify the disposable PostgreSQL service." >&2
  exit 1
fi

expected_migration="$(python3 -c 'from pathlib import Path; print(max(int(p.name.split("__", 1)[0][1:]) for p in Path("src/main/resources/db/migration").glob("V*__*.sql")))')"
database=""
ownership_token=""
created=0

cleanup() {
  exit_code=$?
  if [[ "$created" == "1" ]]; then
    comment="$(docker exec "$container" psql -U drift -d postgres -Atqc \
      "SELECT COALESCE(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '$database'" 2>/dev/null || true)"
    if [[ "$comment" == "$ownership_token" ]]; then
      docker exec "$container" dropdb -U drift --if-exists "$database" || exit_code=1
    else
      echo "Refusing to drop $database because its ownership marker changed." >&2
      exit_code=1
    fi
  fi
  exit "$exit_code"
}
trap cleanup EXIT

create_owned_database() {
  database="drift_cdg289_$(date -u +%Y%m%d%H%M%S)_$(openssl rand -hex 6)"
  ownership_token="$(openssl rand -hex 16)"
  if [[ ! "$database" =~ ^drift_cdg289_[0-9]{14}_[a-f0-9]{12}$ ]]; then
    echo "Generated database name failed the ownership guard." >&2
    return 1
  fi
  if [[ "$(docker exec "$container" psql -U drift -d postgres -Atqc \
    "SELECT 1 FROM pg_database WHERE datname = '$database'")" == "1" ]]; then
    echo "Refusing to reuse existing database $database." >&2
    return 1
  fi
  docker exec "$container" createdb -U drift -O drift "$database"
  created=1
  docker exec "$container" psql -U drift -d postgres -v ON_ERROR_STOP=1 \
    -c "COMMENT ON DATABASE $database IS '$ownership_token'"
}

drop_owned_database() {
  comment="$(docker exec "$container" psql -U drift -d postgres -Atqc \
    "SELECT COALESCE(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '$database'")"
  if [[ "$comment" != "$ownership_token" ]]; then
    echo "Refusing to drop $database because its ownership marker changed." >&2
    return 1
  fi
  docker exec "$container" dropdb -U drift --if-exists "$database"
  created=0
  database=""
  ownership_token=""
}

assert_latest_migration() {
  local actual_version
  actual_version="$(docker exec "$container" psql -U drift -d "$database" -Atqc \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
  if [[ "$actual_version" != "$expected_migration" ]]; then
    echo "Expected the disposable database to reach V$expected_migration, found '$actual_version'." >&2
    return 1
  fi
  echo "Disposable database reached latest checked-in migration V$actual_version."
}

migration_test="src/test/java/com/drift/backend/maritimeai/MaritimeAiAssessmentRecordTypeMigrationTest.java"
if [[ -f "$migration_test" ]]; then
  for test_class in MaritimeAiAssessmentRecordTypeMigrationTest MaritimeAiScenarioAssessmentPersistenceIntegrationTests ScenarioMaritimeAiAssessmentServiceTest MaritimeAiScenarioTransientTest MaritimeAiAssessmentHistoryAuthorizationIntegrationTests MaritimeAiAssessmentHistoryConcurrencyIntegrationTests; do
    if [[ ! -f "src/test/java/com/drift/backend/maritimeai/$test_class.java" ]]; then
      echo "Expected migration test $test_class is missing from this branch." >&2
      exit 1
    fi
  done

  create_owned_database
  export DRIFT_CDG289_JDBC_URL="jdbc:postgresql://127.0.0.1:5432/$database"
  export DRIFT_CDG289_OWNERSHIP_TOKEN="$ownership_token"
  bash ./mvnw -B -ntp \
    -Dtest=MaritimeAiAssessmentRecordTypeMigrationTest,MaritimeAiScenarioAssessmentPersistenceIntegrationTests,ScenarioMaritimeAiAssessmentServiceTest,MaritimeAiScenarioTransientTest \
    test
  assert_latest_migration
  drop_owned_database

  create_owned_database
  export DRIFT_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$database"
  bash ./mvnw -B -ntp \
    -Dtest=MaritimeAiAssessmentHistoryAuthorizationIntegrationTests,MaritimeAiAssessmentHistoryConcurrencyIntegrationTests \
    test
  assert_latest_migration
  drop_owned_database
elif (( expected_migration >= 34 )); then
  echo "V$expected_migration is present but the CDG-289 migration test suite is missing." >&2
  exit 1
else
  create_owned_database
  export DRIFT_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$database"
  bash ./mvnw -B -ntp -Dtest=RecommendationDecisionHistorySchemaIntegrationTests test
  assert_latest_migration
  drop_owned_database
fi
