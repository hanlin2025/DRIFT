# GitHub Actions CI

The CI workflow runs for pull requests targeting main or feature/CDG-290-clean-integrated-demo-candidate. It does not deploy, publish images, or modify Render configuration.

## Checks

- Backend uses Temurin Java 25 and the repository Maven wrapper (Maven 3.9.16). It provisions the test-only databases required by guarded integration tests, runs the complete Maven test suite against a fresh PostgreSQL 16.15 service, then confirms the latest migration checked into that branch was applied (V33 on main and V34 on the #232 candidate at the time of writing). Every additional database has a unique name and ownership marker, and cleanup drops only databases whose marker still matches. It provisions a separate CDG-198 V24-to-V25 migration database when that opt-in integration test is present. The candidate branch also gets separate CDG-198 activation-service, concurrency, and race databases; CDG-279 synthetic-disruption database; and CDG-289 assessment-persistence database. It exports each suite's expected JDBC URL and ownership token, plus required credentials.
- Database and Flyway selects migration tests from the checked-out branch. Where the CDG-289 V34 tests are present, it runs migration, persistence, history-authorization, and concurrency tests against uniquely named, ownership-marked disposable databases. Before those candidate changes are present on main, it runs the recommendation-history schema integration test against its own disposable database. Both paths assert the branch’s latest checked-in migration and drop only databases whose ownership marker still matches.
- Frontend uses the Node 22 release line required by the frontend lockfile, runs npm ci, Vitest, and npm run build. The build script includes tsc --noEmit.
- Docker build job builds backend/Dockerfile with repository-root context when present, preserving the deploy/backend.Dockerfile and deploy/frontend.Dockerfile paths. If none of these files exists in the checked-out PR, it reports that there is nothing to build.

The PostgreSQL service starts fresh for each job. The backend test job sets a disposable test JWT, disables AIS, external AI inference, and risk-alert email dispatch, and directs SMTP to loopback port 1. Migration tests use the disposable service's admin connection to create their own guarded databases. No production credentials, provider keys, paid API requests, Render secrets, or shared database are used. Workflow permissions are limited to repository contents read; checkout credentials are not persisted.

## Reproduce locally

Prerequisites: Java 25, Docker Compose, and Node 22.

Backend and its full PostgreSQL integration suite:

    docker run --detach --name drift-ci-postgres \
      --env POSTGRES_DB=drift_test --env POSTGRES_USER=drift --env POSTGRES_PASSWORD=drift \
      --env 'POSTGRES_INITDB_ARGS=--set=max_connections=500' \
      --publish 5432:5432 --publish 55433:5432 postgres:16.15
    trap 'docker stop drift-ci-postgres >/dev/null 2>&1 || true' EXIT
    POSTGRES_CONTAINER_ID=drift-ci-postgres bash scripts/ci/backend-checks.sh

On the #232 candidate branch, focused V34 and CDG-289 verification uses the repository's ownership-guarded helper. Start the same disposable PostgreSQL container above, then run:

    bash scripts/test-cdg289-scenario-assessment-persistence.sh

Frontend:

    cd frontend
    npm ci --no-audit --no-fund
    npm test -- --maxWorkers=2
    npm run build

The local full-suite helper expects both published PostgreSQL ports shown above, including 55433 for the guarded CDG-206 database. The focused CDG-289 helper uses the repository's `drift-postgres` Compose container. On main before V34 is integrated, the CI migration job runs the checked-in V33 integration test instead of candidate-only tests. Do not point these commands at a shared or production database.

## Required checks

After the workflow is present on the target branch, configure branch protection or a repository ruleset separately for main and the #232 candidate branch. Require the successful `Backend`, `Database and Flyway`, and `Frontend` checks before merge, along with the team's existing review rules. `Docker builds (when configured)` can be required once deployment Dockerfiles are part of the branch; its job reports a successful no-op when those files are absent.

This PR does not change repository rules or merge settings. Configure required checks only after the first workflow run has created the check names.

## Render later

No Render deploy is connected here. If deployment is approved later, keep it behind a separate post-merge workflow or Render's branch deployment controls for a protected branch. Require the CI checks to pass, use an approval-protected GitHub Environment, and store any Render deploy hook as an environment secret. Never trigger deployment from a pull request or make Render credentials available to CI test jobs.
