#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
  echo "Usage: $0 /tmp/drift-cdg290-offline-<run-id>/demo.env" >&2
  exit 2
fi
env_file="$1"
case "$env_file" in /tmp/drift-cdg290-offline-*/demo.env) ;; *) echo "Refusing a configuration file outside the generated demo directory." >&2; exit 2 ;; esac
[[ -f "$env_file" ]] || { echo "Demo environment file not found; nothing to clean."; exit 0; }
# shellcheck disable=SC1090
source "$env_file"
runtime_dir="$(dirname "$env_file")"
[[ "$runtime_dir" == "/tmp/drift-cdg290-offline-${RUN_ID}" ]] || { echo "Runtime path does not match its generated run ID." >&2; exit 1; }
label="$(docker inspect --format '{{ index .Config.Labels "drift.demo" }}' "$CONTAINER_NAME" 2>/dev/null || true)"
run_label="$(docker inspect --format '{{ index .Config.Labels "drift.demo.run" }}' "$CONTAINER_NAME" 2>/dev/null || true)"
if [[ -n "$label" || -n "$run_label" ]]; then
  [[ "$label" == cdg290-offline && "$run_label" == "$RUN_ID" ]] || { echo "Container identity mismatch; refusing cleanup." >&2; exit 1; }
  docker stop "$CONTAINER_NAME" >/dev/null
fi
rm -rf -- "$runtime_dir"
echo "Removed only disposable CDG-290 run ${RUN_ID}; no named volumes or other databases were touched."
