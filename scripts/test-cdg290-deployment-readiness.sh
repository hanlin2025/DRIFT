#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
for command in docker node npm python3 curl; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 2; }
done

RUN_ID="$(python3 -c 'import secrets; print(secrets.token_hex(4))')"
NETWORK="drift-deploy-readiness-${RUN_ID}"
DB_CONTAINER="${NETWORK}-postgres"
API_CONTAINER="${NETWORK}-api"
IMAGE="drift-deploy-readiness:${RUN_ID}"
API_PORT="${DRIFT_READINESS_API_PORT:-18080}"
DB_PASSWORD="DEPLOY_READINESS_DB_PASSWORD_NOT_REAL_${RUN_ID}"
JWT_SECRET="DEPLOY_READINESS_JWT_SECRET_NOT_REAL_${RUN_ID}"
DEMO_EMAIL="deployment-${RUN_ID}@example.invalid"
DEMO_PASSWORD="DRIFT-Disposable-Smoke-Only-2026!"
OTHER_EMAIL="unauthorized-${RUN_ID}@example.invalid"
OTHER_PASSWORD="DRIFT-Unauthorized-Smoke-Only-2026!"
TEMP_DIR="$(mktemp -d)"

cleanup() {
  docker rm -f "$API_CONTAINER" "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  docker image rm "$IMAGE" >/dev/null 2>&1 || true
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

wait_for_readiness() {
  local attempt
  for attempt in $(seq 1 90); do
    if curl --silent --fail "http://127.0.0.1:${API_PORT}/readyz" -o "$TEMP_DIR/ready.json"; then
      if ! grep -Eq '"components"|"db"' "$TEMP_DIR/ready.json"; then
        echo "PASS public readiness endpoint returns no component detail"
      else
        echo "Readiness response unexpectedly exposed detail" >&2
        cat "$TEMP_DIR/ready.json" >&2
        return 1
      fi
      return 0
    fi
    sleep 2
  done
  docker logs "$API_CONTAINER" >&2 || true
  echo "Backend did not become ready" >&2
  return 1
}

start_api() {
  docker run -d --name "$API_CONTAINER" --network "$NETWORK" \
    -p "127.0.0.1:${API_PORT}:10000" \
    -e PORT=10000 \
    -e SPRING_PROFILES_ACTIVE=production \
    -e SPRING_DATASOURCE_URL='jdbc:postgresql://postgres:5432/drift?sslmode=disable' \
    -e SPRING_DATASOURCE_USERNAME=drift \
    -e SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
    -e JWT_SECRET="$JWT_SECRET" \
    -e AIS_ENABLED=false \
    -e MARITIME_AI_ENABLED=false \
    -e MARITIME_AI_SCENARIO_ENABLED=false \
    -e MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=false \
    -e MARITIME_CONTEXT_CONTROLLED_REPLAY_ENABLED=false \
    "$@" "$IMAGE" >/dev/null
}

assert_migration_v34() {
  local latest
  latest="$(docker exec "$DB_CONTAINER" psql -U drift -d drift -At -c \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
  [[ "$latest" == "34" ]] || { echo "Expected Flyway V34, found ${latest:-none}" >&2; return 1; }
  echo "PASS Flyway successfully applied through V34"
}

operational_snapshot() {
  docker exec "$DB_CONTAINER" psql -U drift -d drift -At -c "
    SELECT 'shipments=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM shipments r), ''))
    UNION ALL SELECT 'vessel_observations=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM vessel_observations r), ''))
    UNION ALL SELECT 'shipment_risk_states=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM shipment_risk_states r), ''))
    UNION ALL SELECT 'shipment_risk_transitions=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM shipment_risk_transitions r), ''))
    UNION ALL SELECT 'shipment_change_events=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM shipment_change_events r), ''))
    UNION ALL SELECT 'recommendation_decisions=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM recommendation_decisions r), ''))
    UNION ALL SELECT 'recommendation_decision_history=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM recommendation_decision_history r), ''))
    UNION ALL SELECT 'maritime_ai_assessments=' || md5(COALESCE((SELECT string_agg(to_jsonb(r)::text, E'\\n' ORDER BY to_jsonb(r)::text) FROM maritime_ai_assessments r), ''))
    ORDER BY 1"
}

printf '%s\n' 'Building Render production image...'
docker build --file backend/Dockerfile --tag "$IMAGE" .
echo "PASS backend production container image builds"

docker network create "$NETWORK" >/dev/null
docker run --detach --name "$DB_CONTAINER" --network "$NETWORK" --network-alias postgres \
  --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid,size=1g \
  -e POSTGRES_DB=drift -e POSTGRES_USER=drift -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  postgres:16-alpine >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$DB_CONTAINER" pg_isready -U drift -d drift >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec "$DB_CONTAINER" pg_isready -U drift -d drift >/dev/null
PG_MAJOR="$(docker exec "$DB_CONTAINER" psql -U drift -d drift -At -c 'SHOW server_version_num')"
[[ "$PG_MAJOR" == 16* ]] || { echo "Expected PostgreSQL 16, got $PG_MAJOR" >&2; exit 1; }
echo "PASS isolated PostgreSQL 16 disposable database starts"

start_api
echo "PASS backend starts with production profile"
wait_for_readiness
curl --silent --show-error --fail "http://127.0.0.1:${API_PORT}/livez" -o "$TEMP_DIR/live.json"
echo "PASS public liveness endpoint returns HTTP 200"
AUTH_STATUS="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:${API_PORT}/api/profile")"
[[ "$AUTH_STATUS" == 401 ]] || { echo "Expected unauthenticated API HTTP 401; got $AUTH_STATUS" >&2; exit 1; }
echo "PASS authenticated API remains protected"
assert_migration_v34

read -r DEMO_INVITE DEMO_INVITE_HASH < <(python3 - <<'PY'
import hashlib, secrets
invite = secrets.token_urlsafe(32)
print(invite, hashlib.sha256(invite.encode()).hexdigest())
PY
)
read -r OTHER_INVITE OTHER_INVITE_HASH < <(python3 - <<'PY'
import hashlib, secrets
invite = secrets.token_urlsafe(32)
print(invite, hashlib.sha256(invite.encode()).hexdigest())
PY
)
docker exec -i "$DB_CONTAINER" psql -U drift -d drift -v ON_ERROR_STOP=1 \
  -v demo_email="$DEMO_EMAIL" -v demo_hash="$DEMO_INVITE_HASH" \
  -v other_email="$OTHER_EMAIL" -v other_hash="$OTHER_INVITE_HASH" >/dev/null <<'SQL'
BEGIN;
SELECT id AS company_id FROM companies WHERE code = 'HARBOURLINE_DEMO' AND active = TRUE \gset
INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
VALUES (lower(trim(:'demo_email')), :company_id, 'FREIGHT_FORWARDER', :'demo_hash', NOW() + INTERVAL '1 hour');
SELECT id AS other_company_id FROM companies WHERE code = 'STRAITS_FRESH_DEMO' AND active = TRUE \gset
INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
VALUES (lower(trim(:'other_email')), :other_company_id, 'IMPORTER', :'other_hash', NOW() + INTERVAL '1 hour');
COMMIT;
SQL

(
  cd frontend
  npm ci
  VITE_CONTROLLED_SCENARIO_ENABLED=true VITE_SCENARIO_AI_ENABLED=true \
    MARITIME_AI_API_KEY="FRONTEND_SECRET_SENTINEL_${RUN_ID}" \
    AISSTREAM_API_KEY="FRONTEND_AIS_SENTINEL_${RUN_ID}" \
    npm run build
)
echo "PASS Vite production build completes with demo feature flags"
if grep -R -F -e "FRONTEND_SECRET_SENTINEL_${RUN_ID}" -e "FRONTEND_AIS_SENTINEL_${RUN_ID}" frontend/dist; then
  echo "Backend provider/AIS secret reached frontend build artifacts" >&2
  exit 1
fi
echo "PASS backend secrets are absent from frontend artifacts"

SEED_OUTPUT="$(DRIFT_API_ORIGIN="http://127.0.0.1:${API_PORT}" \
  DRIFT_DEMO_EMAIL="$DEMO_EMAIL" DRIFT_DEMO_PASSWORD="$DEMO_PASSWORD" \
  DRIFT_DEMO_INVITE_TOKEN="$DEMO_INVITE" DRIFT_CREATE_DEMO_SHIPMENTS=true \
  node scripts/test-cdg290-vercel-proxy.mjs)"
printf '%s\n' "$SEED_OUTPUT" | sed '/^DEPLOY_SMOKE_IDS=/d'
IDS_JSON="$(printf '%s\n' "$SEED_OUTPUT" | sed -n 's/^DEPLOY_SMOKE_IDS=//p')"
[[ -n "$IDS_JSON" ]] || { echo "Smoke helper did not emit isolated A/B IDs" >&2; exit 1; }
IFS=$'\t' read -r DEMO_USER_ID DEMO_COMPANY_ID SHIPMENT_A_ID SHIPMENT_B_ID < <(
  IDS_JSON="$IDS_JSON" python3 - <<'PY'
import json, os
row = json.loads(os.environ['IDS_JSON'])
print('\t'.join(str(row[key]) for key in ('userId', 'companyId', 'shipmentAId', 'shipmentBId')))
PY
)
RUNTIME_ENV="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$API_CONTAINER")"
for required in 'AIS_ENABLED=false' 'MARITIME_AI_ENABLED=false' 'MARITIME_AI_SCENARIO_ENABLED=false' 'MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=false'; do
  grep -Fxq "$required" <<<"$RUNTIME_ENV" || { echo "Missing safe runtime default: $required" >&2; exit 1; }
done
! grep -Eq '^(AISSTREAM_API_KEY|MARITIME_AI_API_KEY)=' <<<"$RUNTIME_ENV"
echo "PASS hosted startup has no provider keys and external AIS/AI gates remain off"

# Sessions live in the one backend process. Recreate it with the exact two synthetic DB bindings.
docker stop --time 30 "$API_CONTAINER" >/dev/null
docker rm "$API_CONTAINER" >/dev/null
start_api \
  -e CONTROLLED_SCENARIO_ENABLED=true \
  -e CONTROLLED_SCENARIO_DEMO_COMPANY_ID="$DEMO_COMPANY_ID" \
  -e CONTROLLED_SCENARIO_ALLOWED_USER_IDS="$DEMO_USER_ID" \
  -e CONTROLLED_SCENARIO_SHIPMENT_A_ID="$SHIPMENT_A_ID" \
  -e CONTROLLED_SCENARIO_SHIPMENT_B_ID="$SHIPMENT_B_ID"
wait_for_readiness
assert_migration_v34

BEFORE_SCENARIO="$(operational_snapshot)"
SCENARIO_OUTPUT="$(DRIFT_API_ORIGIN="http://127.0.0.1:${API_PORT}" \
  DRIFT_DEMO_EMAIL="$DEMO_EMAIL" DRIFT_DEMO_PASSWORD="$DEMO_PASSWORD" \
  DRIFT_TEST_SCENARIO=true DRIFT_SCENARIO_SHIPMENT_A_ID="$SHIPMENT_A_ID" \
  DRIFT_SCENARIO_SHIPMENT_B_ID="$SHIPMENT_B_ID" \
  DRIFT_UNAUTHORIZED_EMAIL="$OTHER_EMAIL" DRIFT_UNAUTHORIZED_PASSWORD="$OTHER_PASSWORD" \
  DRIFT_UNAUTHORIZED_INVITE="$OTHER_INVITE" \
  node scripts/test-cdg290-vercel-proxy.mjs)"
printf '%s\n' "$SCENARIO_OUTPUT"
AFTER_SCENARIO="$(operational_snapshot)"
[[ "$BEFORE_SCENARIO" == "$AFTER_SCENARIO" ]] || {
  echo "Operational tables changed during scenario session lifecycle" >&2
  diff -u <(printf '%s\n' "$BEFORE_SCENARIO") <(printf '%s\n' "$AFTER_SCENARIO") >&2 || true
  exit 1
}
echo "PASS scenario lifecycle did not mutate AIS, shipment, risk, change, recommendation or AI tables"

# Restart a configured production process against the migrated database.
docker stop --time 30 "$API_CONTAINER" >/dev/null
docker start "$API_CONTAINER" >/dev/null
wait_for_readiness
assert_migration_v34
echo "PASS clean backend shutdown, restart and migration revalidation"

# Missing required deployment configuration must fail fast rather than bind a local default.
if docker run --rm --network "$NETWORK" --entrypoint sh \
  -e SPRING_PROFILES_ACTIVE=production -e PORT=10000 \
  "$IMAGE" -c 'exec java -jar /app/app.jar --spring.profiles.active=production --server.port=10000' \
  >"$TEMP_DIR/missing-config.log" 2>&1; then
  echo "Production process unexpectedly started without datasource/JWT configuration" >&2
  exit 1
fi
grep -Eq 'SPRING_DATASOURCE_URL|JWT_SECRET|placeholder' "$TEMP_DIR/missing-config.log" || {
  echo "Production configuration failure did not identify missing required configuration" >&2
  cat "$TEMP_DIR/missing-config.log" >&2
  exit 1
}
echo "PASS missing production configuration fails during startup"

! docker logs "$API_CONTAINER" 2>&1 | grep -Fq "$DB_PASSWORD"
! docker logs "$API_CONTAINER" 2>&1 | grep -Fq "$JWT_SECRET"
echo "PASS test credentials are absent from backend logs"

(
  cd frontend
  if VERCEL=1 env -u DRIFT_VERCEL_DEPLOY_APPROVED npm run build >"$TEMP_DIR/vercel-gate.log" 2>&1; then
    echo "Vercel build should be blocked until deployment approval is explicit" >&2
    exit 1
  fi
  grep -Fq 'Vercel build blocked' "$TEMP_DIR/vercel-gate.log"
  echo "PASS Vercel build fails closed without explicit approval"
  VERCEL=1 DRIFT_VERCEL_DEPLOY_APPROVED=true npm run build >/dev/null
  echo "PASS explicit Vercel build authorization permits production build"
)
