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

cd "$(dirname "$0")/../../frontend"
export DRIFT_API_ORIGIN="http://127.0.0.1:${BACKEND_PORT}"
export DRIFT_E2E_WEB_PORT="$FRONTEND_PORT"
export VITE_MAP_STYLE_URL=/cdg290-offline-style.json
export VITE_CONTROLLED_SCENARIO_ENABLED=true
export VITE_SCENARIO_AI_ENABLED=true
echo "Starting local frontend at http://127.0.0.1:${FRONTEND_PORT}; map style and GeoJSON are served from this checkout."
exec npm run dev -- --host 127.0.0.1 --strictPort
