#!/usr/bin/env bash
set -euo pipefail

build_count=0

if [[ -f backend/Dockerfile ]]; then
	docker build --file backend/Dockerfile --tag drift-backend:ci .
	build_count=$((build_count + 1))
fi

if [[ -f deploy/backend.Dockerfile ]]; then
	docker build --file deploy/backend.Dockerfile --tag drift-backend-deploy:ci .
	build_count=$((build_count + 1))
fi

if [[ -f deploy/frontend.Dockerfile ]]; then
	docker build --file deploy/frontend.Dockerfile --tag drift-frontend:ci .
	build_count=$((build_count + 1))
fi

if (( build_count == 0 )); then
	echo "No deployable Dockerfiles are present; there is nothing to build."
else
	echo "Built $build_count deployable Docker image(s)."
fi
