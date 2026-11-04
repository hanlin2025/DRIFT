#!/usr/bin/env bash
set -euo pipefail
umask 077

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is required for the disposable CDG-290 demo database." >&2
  exit 1
fi

run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}"
runtime_dir="/tmp/drift-cdg290-offline-${run_id}"
container_name="drift-cdg290-offline-${run_id}"
mkdir -m 700 "$runtime_dir"
db_name="cdg290_demo"
db_user="cdg290_demo"
db_password="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
jwt_secret="$(od -An -N48 -tx1 /dev/urandom | tr -d ' \n')"

cleanup_failed_start() {
  docker stop "$container_name" >/dev/null 2>&1 || true
  rm -rf -- "$runtime_dir"
}
trap cleanup_failed_start ERR

docker run --detach --rm \
  --name "$container_name" \
  --label drift.demo=cdg290-offline \
  --label "drift.demo.run=${run_id}" \
  --publish 127.0.0.1::5432/tcp \
  --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid,size=512m \
  --env POSTGRES_DB="$db_name" \
  --env POSTGRES_USER="$db_user" \
  --env POSTGRES_PASSWORD="$db_password" \
  postgres:16.15 >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "$container_name" pg_isready --username "$db_user" --dbname "$db_name" >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    docker logs "$container_name" >&2
    exit 1
  fi
  sleep 1
done

db_port="$(docker port "$container_name" 5432/tcp | sed 's/.*://')"
cat >"$runtime_dir/demo.env" <<EOF
RUN_ID=${run_id}
CONTAINER_NAME=${container_name}
DB_NAME=${db_name}
DB_USER=${db_user}
DB_PASSWORD=${db_password}
DB_PORT=${db_port}
JWT_SECRET=${jwt_secret}
BACKEND_PORT=18081
FRONTEND_PORT=15174
DEMO_COMPANY_ID=29000
DEMO_USER_ID=29001
DEMO_SHIPMENT_A_ID=29001
DEMO_SHIPMENT_B_ID=29002
DEMO_EMAIL=cdg290.offline@example.test
EOF
chmod 600 "$runtime_dir/demo.env"
trap - ERR

echo "Disposable PostgreSQL 16.15 is ready on 127.0.0.1:${db_port}."
echo "Runtime configuration: ${runtime_dir}/demo.env"
echo "Data lives only in this labelled container's tmpfs and is deleted on cleanup."
