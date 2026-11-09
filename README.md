# DRIFT

DRIFT is a transshipment connection risk engine. This repository is a monorepo containing the backend and frontend.

## Prerequisites

Before you run the project, install:

- **Git**
- **JDK 25** (required by `backend/pom.xml`)
- **Docker Desktop** (includes Docker Compose)
- **Node.js 22.12+** (Node 24 LTS recommended) for the frontend
- **Python 3** for the local invitation helper
- Optional: IntelliJ IDEA or VS Code

You do **not** need to install Maven or PostgreSQL separately. The project uses the Maven Wrapper and Docker Compose.

## Project Structure

```
DRIFT/
├── backend/                 # Spring Boot backend
│   ├── mvnw                 # Maven Wrapper (Mac/Linux)
│   ├── mvnw.cmd             # Maven Wrapper (Windows)
│   └── src/main/resources/
│       ├── application.yaml
│       └── db/migration/    # Flyway migrations go here
├── frontend/                # React + TypeScript signup interface
├── docker-compose.yml       # Local PostgreSQL and Mailpit
└── README.md
```

## Local Setup (WSL / Linux)

Use WSL terminals for a repository stored under `/home/...`. Node, npm and Java must
run inside WSL. Windows npm cannot run this project from a `\\wsl.localhost\...` path.

### Check your runtimes

```bash
java -version
node --version
node -p 'process.platform'
command -v npm
```

Java must be 25, Node must be 22.12+ (24 LTS recommended), and the platform must be
`linux`. The npm path must not point into `/mnt/c/Program Files/nodejs`.
Install Node inside WSL if these checks fail, then open a new WSL terminal. On the
current development machine, Linux Node is installed in `~/.local/share/drift-node`;
existing terminals can activate it with:

```bash
export PATH="$HOME/.local/share/drift-node/bin:$PATH"
hash -r
```

### Terminal 1: database and backend

Start in the repository root (`DRIFT/`). Keep Docker Desktop running with WSL integration
for your Ubuntu distribution enabled.

```bash
docker compose up -d postgres mailpit
docker compose exec -T postgres pg_isready -U drift -d drift
```

Wait until PostgreSQL reports `accepting connections`. Docker creates the `drift`
database on first initialisation. The backend already uses it in `application.yaml`;
no datasource environment variable is needed. The credentials are username `drift`,
password `drift`, port `5432`.

Login sessions are signed with `JWT_SECRET`. Set it in the same terminal before
starting the backend. Use at least 32 bytes, and do not commit the value. The
backend does not start when it is missing.

```bash
export JWT_SECRET='replace-with-a-local-secret-at-least-32-bytes'
cd backend
bash ./mvnw spring-boot:run
```

Keep this terminal running. Wait for `Started BackendApplication`; the API uses
http://localhost:8080. Opening the API root in a browser is not a frontend test and may
return 401 because only the registration and login endpoints are public.

### Terminal 2: frontend

Open another WSL terminal in the repository root:

```bash
cd frontend
npm ci
npm run dev
```

Keep this terminal running. Open http://localhost:5173/signup. Vite forwards `/api`
requests to the backend on port 8080. Do not start another copy if these ports are already
in use; stop the previous development process with Ctrl+C in its terminal first.

### Terminal 3: create a local invitation

From the repository root, after the backend has started:

```bash
python3 scripts/create-invitation.py --email alex@example.com --company HARBOURLINE_DEMO --role FREIGHT_FORWARDER
```

Open the printed signup link. A plain `/signup` URL asks for an invitation code.
The helper does not send email. See [registration setup](docs/registration.md) for details.

### Tests and shutdown

From the repository root, `bash scripts/test-backend.sh` starts PostgreSQL and runs tests
against the separate `drift_test` database. In `frontend/`, run `npm test` and
`npm run build`. Playwright is optional for running the app.

Stop frontend and backend with Ctrl+C in their respective terminals. Only after stopping
them, use `docker compose down` if you also want to stop PostgreSQL and Mailpit. Do not add `-v`
unless you intend to delete the database volume.

### Native Windows development

For a clone stored on a Windows drive (not a WSL network path), use Windows Java/Node and
PowerShell. After starting PostgreSQL, run in `backend/`:

```powershell
$env:JWT_SECRET = 'replace-with-a-local-secret-at-least-32-bytes'
.\mvnw.cmd spring-boot:run
```

Use a separate PowerShell terminal in `frontend/` for `npm ci` and `npm run dev`.
Do not mix Windows and Linux Node installations for the same `node_modules` directory.

## Database Migrations

All schema changes must go through Flyway.

- Put SQL migration files in `backend/src/main/resources/db/migration/`.
- Use the naming convention `V1__description.sql`, `V2__description.sql`, etc.
- Do **not** change `spring.jpa.hibernate.ddl-auto` from `validate`. Hibernate should only validate the schema, not create it.

## Branching

- `main` is the stable branch. Do not push directly to `main`.
- Create a feature branch for each Jira story or task:
  - `feature/DRIFT-2-register`
  - `feature/DRIFT-3-login`
  - `chore/update-readme`
- Open a pull request when your work is ready for review.

## Troubleshooting

- **`UNC paths are not supported` / `'vite' is not recognized`**: Windows npm is running
  inside WSL. Activate/install Linux Node, confirm `node -p 'process.platform'` prints
  `linux`, then rerun `npm ci` and `npm run dev` in `frontend/`.
- **Flyway `Migration checksum mismatch for migration version 1`**: the database was
  created by an older version of V1. See the one-time local database reset in
  [registration setup](docs/registration.md#existing-development-databases). It keeps
  the database name `drift` and deletes that database's existing data.
- **`Port 5173 is already in use` / `Port 8080 was already in use`**: another development
  server is running. Use that instance or stop it before starting a second one.
- **Database connection refused**: start Docker Desktop and PostgreSQL, then wait for
  `pg_isready` to report `accepting connections`.
- **`./mvnw: Permission denied`**: use `bash ./mvnw ...` in `backend/`.
- **PowerShell does not recognise `mvnw.cmd`**: use `.\mvnw.cmd` in `backend/`.

## Risk-alert email

The shipment-risk email is the message CDG-49 already sends. It stays off until this terminal turns it on. Mailpit is the mailbox started with Postgres above: SMTP port `1025`, which `spring.mail` already uses, and a web view at http://localhost:8025.

`RISK_ALERTS_FROM` defaults to `noreply@localhost`. `RISK_ALERTS_FRONTEND_BASE_URL` defaults to `http://localhost:5173`. Both match the local password-reset values and can be overridden in this terminal. Do not commit either value. The link is built from that origin plus the existing role path. It is not hardcoded in the mail composer.

`RISK_ALERTS_ACTIVATION_COMMAND` is the one-time activation command. Run it once, in its own process, before the server you want to keep. Do not leave it set on that server. The command baselines the current connection risk of every active shipment, suppresses unsent deliveries from an older generation with "Suppressed during risk-alert activation", sets the activation generation to 1, and then the process exits. A normal startup does not create a generation. A delivery recorded under another generation is suppressed as "Risk alert delivery predates the active activation generation" and is not sent. Turning the mail switches on does not activate the gate.

```bash
export JWT_SECRET='replace-with-a-local-secret-at-least-32-bytes'
export RISK_ALERTS_ACTIVATION_COMMAND=true
cd backend
bash ./mvnw spring-boot:run
```

Wait until that process exits. Then start the server with the mail switches and the evaluation switch together:

```bash
unset RISK_ALERTS_ACTIVATION_COMMAND
export RISK_ALERTS_MAIL_ENABLED=true
export RISK_ALERTS_DISPATCH_ENABLED=true
export RISK_ALERTS_EVALUATION_ENABLED=true
bash ./mvnw spring-boot:run
```

With the mail switches unset, a due delivery is suppressed as "Risk alert mail is disabled" and is not retried. Dispatch disabled does not open an SMTP connection.

`RISK_ALERTS_EVALUATION_ENABLED` stays unset on a server that should not sweep. The 15-minute sweep runs only when that switch is set and the activation gate is already active. Creating a shipment, importing one, or changing the planned arrival or feeder departure still evaluates after the write commits. An AIS position does not start an evaluation.

On Windows, set the same switches with `$env:RISK_ALERTS_MAIL_ENABLED = 'true'`, `$env:RISK_ALERTS_DISPATCH_ENABLED = 'true'`, and `$env:RISK_ALERTS_EVALUATION_ENABLED = 'true'`, and clear the activation command with `Remove-Item Env:RISK_ALERTS_ACTIVATION_COMMAND` before the server you want to keep.

## Account Registration

See [registration setup](docs/registration.md) for preset companies, local invitation links,
API details, database migration notes, and registration integration tests.

## First administrator

A new installation does not have an administrator until an operator creates one. Set
`DRIFT_SETUP_TOKEN` to a private value of at least 32 bytes, start the backend, and open
http://localhost:5173/setup. The credential is not stored in the repository or the browser.
After that one administrator exists, setup refuses a second account. Colleagues still join
by invitation. See [first administrator setup](docs/first-administrator.md).
An administrator then invites a colleague from User management. See [administrator invitations](docs/admin-invitation.md).
A signed-in browser stays signed in across tabs for eight hours, until sign-out. See [browser sessions](docs/browser-session.md).

## Frontend

In a second terminal, run `cd frontend`, `npm ci`, then `npm run dev`.
Open http://localhost:5173/signup using a link from the invitation helper.
See [frontend setup](frontend/README.md) for tests and login-route scope.

## Optional AIS Stream Connection

CDG-64 can open a backend-only connection to AIS Stream when it is explicitly enabled. Create an AIS Stream API key, then set the key and the two corners of a focused geographic bounding box in the same terminal as the backend:

```bash
export AIS_ENABLED=true
export AISSTREAM_API_KEY='your-ais-stream-key'
export AIS_SOUTHWEST_LATITUDE='your-southwest-latitude'
export AIS_SOUTHWEST_LONGITUDE='your-southwest-longitude'
export AIS_NORTHEAST_LATITUDE='your-northeast-latitude'
export AIS_NORTHEAST_LONGITUDE='your-northeast-longitude'
```

The connector subscribes only to `PositionReport` messages. It remains disabled by default. While the backend is running with AIS enabled, each valid report is kept in memory and can be read by MMSI or vessel name through `LatestAisPositions`. Shipment detail reads that set for the mother vessel and the feeder vessel. `GET /api/shipments/{id}/tracking` returns the same latest fix for each vessel, including coordinates, speed, course, heading, and ingestion time, and leaves a position null when none is retained. A stored name with an `MV` or `M/V` prefix also matches the AIS name without that prefix. The planned connection window stays the gap in the stored schedule. Shipment detail also returns the remaining connection window, the planned feeder departure minus the mother vessel's estimated arrival, when that estimate has an arrival time and is not possibly outdated. It is calculated when the shipment is read and is not stored. The shipment detail page shows it beside the planned connection window. Risk uses that remaining window when it is present and otherwise the planned window. The shipment list does not include it. Each valid report is also stored in the `vessel_observations` table with its MMSI, coordinates, speed, course, heading, ingestion time and `source`. Reports from the livestream are stored as `AIS_STREAM`; `SEEDED` marks seeded or synthetic observations. A report that cannot be stored is logged and skipped without closing the stream. A vessel name keeps the newest fix for the MMSI that currently reports that name. A later report that omits the name stays with that vessel, and a renamed vessel no longer answers for its previous name. After a restart, a lookup reads the newest observation for each MMSI from `vessel_observations` and matches the name that vessel currently reports.

For candidate selection and bounded export of real provider observations, see the [CDG-285 AIS vessel selection and capture runbook](docs/cdg285-ais-vessel-selection-and-capture.md).

`GET /api/shipments/{id}/track-history` returns the recent track of one vessel on a visible shipment, built from `vessel_observations` in time order. Each point carries its observation `id`, which stays unique when two reports share a timestamp; ties are ordered by that id. `vessel` is `MOTHER` (default) or `FEEDER`, and `hours` is one of the preset windows `6`, `24` (default) or `72`; any other value returns 400. An optional ISO-8601 `cutoff` uses DRIFT receipt time, not AIS event time. Cutoff playback without `captureId` is limited to `SEEDED` rehearsal observations; authentic `AIS_STREAM` playback requires an accepted CDG-285 capture manifest, matching shipment IMO/MMSI, approved replay permission, and unexpired retention. Invalid positions are omitted, ties are preserved, no gaps are interpolated, and responses cap at 500 points. The response includes provenance and availability metadata. See the [CDG-286 recorded track runbook](docs/cdg286-recorded-ais-track-playback.md) for the capture checks and full contract. A shipment the company may not view returns 404.
