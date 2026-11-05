#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
  echo "Usage: $0 /tmp/drift-cdg290-offline-<run-id>/demo.env" >&2
  exit 2
fi
env_file="$1"
case "$env_file" in /tmp/drift-cdg290-offline-*/demo.env) ;; *) echo "Use the generated demo.env file." >&2; exit 2 ;; esac
[[ -f "$env_file" ]] || { echo "Demo environment file not found." >&2; exit 2; }
# shellcheck disable=SC1090
source "$env_file"
label="$(docker inspect --format '{{ index .Config.Labels "drift.demo" }}' "$CONTAINER_NAME")"
run_label="$(docker inspect --format '{{ index .Config.Labels "drift.demo.run" }}' "$CONTAINER_NAME")"
[[ "$label" == cdg290-offline && "$run_label" == "$RUN_ID" ]] || { echo "Disposable database identity check failed." >&2; exit 1; }
docker exec "$CONTAINER_NAME" pg_isready --username "$DB_USER" --dbname "$DB_NAME" >/dev/null

export JWT_SECRET
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/${DB_NAME}"
export SPRING_DATASOURCE_USERNAME="$DB_USER"
export SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT="$BACKEND_PORT"
export AIS_ENABLED=false AISSTREAM_API_KEY=
export CONTROLLED_SCENARIO_ENABLED=true
export CONTROLLED_SCENARIO_DEMO_COMPANY_ID="$DEMO_COMPANY_ID"
export CONTROLLED_SCENARIO_ALLOWED_USER_IDS="$DEMO_USER_ID"
export CONTROLLED_SCENARIO_SHIPMENT_A_ID="$DEMO_SHIPMENT_A_ID"
export CONTROLLED_SCENARIO_SHIPMENT_B_ID="$DEMO_SHIPMENT_B_ID"
export MARITIME_AI_ENABLED=true
export MARITIME_AI_SCENARIO_ENABLED=true
export MARITIME_AI_CONTROLLED_REPLAY_STUB_ENABLED=true
export MARITIME_AI_PROVIDER= MARITIME_AI_MODEL= MARITIME_AI_ENDPOINT= MARITIME_AI_API_KEY=
export MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=false
export MARITIME_AI_AUTHENTIC_RETROSPECTIVE_INFERENCE_APPROVED=false
export MARITIME_CONTEXT_CONTROLLED_REPLAY_ENABLED=false
export RISK_ALERTS_EVALUATION_ENABLED=false RISK_ALERTS_DISPATCH_ENABLED=false

cd "$(dirname "$0")/../../backend"
echo "Starting local backend on 127.0.0.1:${BACKEND_PORT}; providers remain disabled and the offline scenario stub is explicitly enabled."
exec ./mvnw spring-boot:run
