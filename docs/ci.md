# GitHub Actions CI

The CI workflow runs for pull requests targeting main or feature/CDG-290-clean-integrated-demo-candidate. It does not deploy, publish images, or modify Render configuration.

## Checks

- Backend uses Temurin Java 25 and the repository Maven wrapper (Maven 3.9.16). It runs the complete Maven test suite against a fresh PostgreSQL 16.15 service and confirms Flyway applied V34.
- Database and Flyway runs the existing CDG-289 migration, persistence, history-authorization, and concurrency integration-test classes against uniquely named, ownership-marked disposable databases. It asserts the latest applied Flyway version is V34 and drops only databases whose ownership marker still matches.
- Frontend uses the Node 22 release line required by the frontend lockfile, runs npm ci, Vitest, and npm run build. The build script includes tsc --noEmit.
- Docker build job builds deploy/backend.Dockerfile and/or deploy/frontend.Dockerfile when present. If neither exists in the checked-out PR, it reports that there is nothing to build.

The PostgreSQL service starts fresh for each job. The test jobs set a disposable test JWT, disable AIS, AI inference, and risk-alert email dispatch, and direct SMTP to loopback port 1. No production credentials, provider keys, paid API requests, Render secrets, or shared database are used. Workflow permissions are limited to repository contents read; checkout credentials are not persisted.

## Reproduce locally

Prerequisites: Java 25, Docker Compose, and Node 22.

Backend and its full PostgreSQL integration suite:

    bash scripts/test-backend.sh

The focused V34 and CDG-289 database verification uses the repository's ownership-guarded helper:

    bash scripts/test-cdg289-scenario-assessment-persistence.sh

Frontend:

    cd frontend
    npm ci --no-audit --no-fund
    npm test -- --maxWorkers=2
    npm run build

The local database helper uses Docker Compose and the same disposable local PostgreSQL setup as the project scripts. Do not point these commands at a shared or production database.

## Required checks

After the workflow is present on the target branch, configure branch protection or a repository ruleset separately for main and the #232 candidate branch. Require the successful CI / Backend, CI / Database and Flyway, and CI / Frontend checks before merge, along with the team's existing review rules. CI / Docker builds (when configured) can be required once deployment Dockerfiles are part of the branch; its job reports a successful no-op when those files are absent.

This PR does not change repository rules or merge settings. Configure required checks only after the first workflow run has created the check names.

## Render later

No Render deploy is connected here. If deployment is approved later, keep it behind a separate post-merge workflow or Render's branch deployment controls for a protected branch. Require the CI checks to pass, use an approval-protected GitHub Environment, and store any Render deploy hook as an environment secret. Never trigger deployment from a pull request or make Render credentials available to CI test jobs.
