# DRIFT

DRIFT helps freight forwarders, importers, logistics managers, and administrators manage
transshipment shipments and understand connection risk. It is a Java/Spring Boot API, a
React/TypeScript web app, and a PostgreSQL database managed with Flyway.

The core connection-risk calculation is deterministic: it uses shipment schedules and,
when a usable AIS position is available, an estimated mother-vessel arrival. It is not an
AI prediction. Optional maritime assessments can interpret eligible source evidence, but
they are advisory and do not set the core risk level or make operational decisions.

## What the app includes

- Company accounts with administrator, freight-forwarder, importer, and logistics-manager
  roles, plus company and shipment access controls.
- Company signup with email verification, invitations to join an existing company, profile
  and account administration, and password reset.
- Shipment registration and CSV import, importer linking, shipment history, and importer
  delivery deadlines.
- Shipment lists and details with connection windows, risk explanations, vessel tracking,
  recorded AIS track history, and delay comparisons.
- Optional AIS ingestion, maritime source collection and context, persisted evidence-bounded
  AI assessments, and risk-alert email workflows. These integrations have separate
  configuration and approval gates and are off by default.
- Controlled synthetic disruption scenarios for isolated rehearsals. Synthetic evidence
  and stub assessments are not live source data or real model output.

## Run locally

### Prerequisites

- Git
- JDK 25
- Docker with Docker Compose
- Node.js 22.22.2 or newer and npm (Node 24 LTS recommended)
- Python 3 only if using the local invitation helper or maritime collector tools

The Maven Wrapper is included; a separate Maven installation is not needed. Use one
operating system's Java and Node installations for a checkout. For WSL, keep the repository
and runtimes inside WSL rather than running Windows Node against a WSL network path.

### 1. Start PostgreSQL and Mailpit

From the repository root:

```bash
docker compose up -d postgres mailpit
docker compose exec -T postgres pg_isready -U drift -d drift
```

Wait for `accepting connections`. The local database is `drift` with username and password
`drift`. Mailpit accepts SMTP on port `1025` and displays messages at
<http://localhost:8025>.

### 2. Start the backend

In a terminal at the repository root, set a private local signing secret and start Spring
Boot:

```bash
export JWT_SECRET="$(openssl rand -hex 32)"
cd backend
bash ./mvnw spring-boot:run
```

Keep this terminal open. The backend uses `http://localhost:8080`, connects to the local
`drift` database by default, and applies schema migrations through Flyway. Do not commit
the generated secret. In PowerShell, set `$env:JWT_SECRET` before running
`./mvnw.cmd spring-boot:run`.

### 3. Start the frontend

In a second terminal at the repository root:

```bash
cd frontend
npm ci
npm run dev
```

Open <http://localhost:5173/signup>. You can register a new company and its first
administrator, or join an existing company with an invitation. New company accounts must
verify their email; open the message in Mailpit. Then sign in at
<http://localhost:5173/login>.

An operator can instead use the one-time `/setup` flow to create the first administrator
for an active organisation. This requires setting `DRIFT_SETUP_TOKEN` (at least 32 bytes)
in the backend environment; leave it unset when setup is not being performed. See
[first administrator setup](docs/first-administrator.md).

The Vite development server forwards `/api` calls to the backend on port 8080. If either
port is already in use, use the running server or stop it before starting another. Avoid
`docker compose down -v` unless you intend to delete the local database volume.

## Hosted demo and local development

The initial hosted DRIFT login page is available at
<https://drift-seven-red.vercel.app/login>. The checkout's Vercel routing configuration in
[`frontend/vercel.json`](frontend/vercel.json) sends `/api` requests to its configured
Render backend. In local development, Vite sends `/api` requests to the backend at
`http://localhost:8080`.

Local development runs Vite at `http://localhost:5173`, Spring Boot at
`http://localhost:8080`, PostgreSQL in Docker (`drift`), and Mailpit at
`http://localhost:8025`. Local accounts, shipment data, and test emails stay in that local
environment; they are not shared with the hosted site. Local code changes also do not
change the hosted site. The checked-in [`render.yaml`](render.yaml) is a review-only
blueprint; it does not provision or deploy services. See the
[deployment runbook](docs/cdg290-vercel-render-deployment-runbook.md) for hosting status
and controls. Treat the hosted site as an initial demo, not a production workspace.

## Tests and coverage

Frontend commands, from `frontend/`:

```bash
npm test
npm run coverage
npm run build
```

`npm run coverage` runs the Vitest suite with V8 instrumentation. Open
`frontend/coverage/index.html` for the HTML report; the command also prints summary
percentages and writes Clover and JSON reports. Coverage files are generated build output
and are gitignored.

Backend tests, from the repository root:

```bash
bash scripts/test-backend.sh
```

The helper starts PostgreSQL if needed and runs Maven's `verify` lifecycle against the
separate `drift_test` test database. JaCoCo writes the HTML report to
`backend/target/site/jacoco/index.html`. The CI workflow also generates backend and frontend
coverage reports. The backend integration suite uses PostgreSQL; do not point test or
migration commands at a persistent database.

Playwright browser tests are separate from the standard frontend unit suite. See
[frontend testing notes](frontend/README.md) for browser-test commands and database
requirements. See [CI documentation](docs/ci.md) for the pipeline and locally reproducible
checks.

## Configuration boundaries

Optional data collection and model features are deliberately controlled:

- AIS streaming is disabled unless `AIS_ENABLED=true` and its credentials and geographic
  bounds are configured.
- Scheduled MPA/NEA source collection requires provider-specific configuration and access
  described in [the collection runbook](docs/cdg358-scheduled-maritime-collection.md).
  Source observations retain their provenance, timing, compatibility, and uncertainty;
  context records do not automatically alter core shipment risk.
- Maritime AI assessment is disabled by default. Enabling a provider requires explicit
  configuration and the external-request approval gate. Assessments are advisory,
  evidence-bounded, persisted, and subject to human review.
- Risk-alert evaluation and email dispatch are off by default. Activation, evaluation, and
  mail delivery have separate operator-controlled gates; see
  [continuous risk monitoring](docs/cdg198-continuous-risk-monitoring.md) and
  [Gmail SMTP setup](docs/gmail-smtp-production-email.md).
- `render.yaml` describes a review-only hosting blueprint. It does not provision resources
  or authorize a deployment. See the [deployment runbook](docs/cdg290-vercel-render-deployment-runbook.md)
  for the current boundaries.

Do not put API keys, passwords, customer data, or generated secrets in source control. Do
not enable live collectors, external model requests, production email, or hosting changes
without the corresponding provider access and approval.

## Repository layout

```text
backend/       Spring Boot API, domain logic, tests, and Flyway migrations
frontend/      React + TypeScript app, Vitest tests, and Playwright specs
scripts/       Local setup, guarded integration-test, and operational helpers
tools/         Standalone source collectors and offline evaluation tools
docs/          Feature contracts, setup guides, runbooks, and CI documentation
fixtures/      Explicitly synthetic or recorded test/rehearsal data
```

## Useful documentation

- [Registration and invitations](docs/registration.md)
- [Administrator invitations](docs/admin-invitation.md)
- [Shipment CSV format](docs/shipment-import-csv.md)
- [AIS vessel capture](docs/cdg285-ais-vessel-selection-and-capture.md)
- [Recorded AIS track playback](docs/cdg286-recorded-ais-track-playback.md)
- [Maritime AI interpretation and evidence limits](docs/cdg247-ai-maritime-interpretation.md)
- [Scheduled maritime collection](docs/cdg358-scheduled-maritime-collection.md)
- [Risk-alert monitoring and activation](docs/cdg198-continuous-risk-monitoring.md)
- [GitHub Actions CI](docs/ci.md)

## Database changes

Add schema changes as Flyway migrations under
`backend/src/main/resources/db/migration/`, using the repository's
`V<number>__description.sql` naming convention. Hibernate is configured to validate the
schema; do not change it to create or update tables automatically.

## Contributing

`main` is the stable branch. Make focused changes on a feature or chore branch and open a
pull request for review. Ticketed work should follow the project's Jira-linked branch
conventions. Do not merge or deploy unless explicitly requested.
