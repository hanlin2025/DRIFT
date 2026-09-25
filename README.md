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
├── docker-compose.yml       # Local PostgreSQL
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
docker compose up -d postgres
docker compose exec -T postgres pg_isready -U drift -d drift
```

Wait until PostgreSQL reports `accepting connections`. Docker creates the `drift`
database on first initialisation. The backend already uses it in `application.yaml`;
no datasource environment variable is needed. The credentials are username `drift`,
password `drift`, port `5432`.

```bash
cd backend
bash ./mvnw spring-boot:run
```

Keep this terminal running. Wait for `Started BackendApplication`; the API uses
http://localhost:8080. Opening the API root in a browser is not a frontend test and may
return 403 because only the registration endpoints are public.

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
them, use `docker compose down` if you also want to stop PostgreSQL. Do not add `-v`
unless you intend to delete its database volume.

### Native Windows development

For a clone stored on a Windows drive (not a WSL network path), use Windows Java/Node and
PowerShell. After starting PostgreSQL, run in `backend/`:

```powershell
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

## Account Registration

See [registration setup](docs/registration.md) for preset companies, local invitation links,
API details, database migration notes, and registration integration tests.

## Frontend

In a second terminal, run `cd frontend`, `npm ci`, then `npm run dev`.
Open http://localhost:5173/signup using a link from the invitation helper.
See [frontend setup](frontend/README.md) for tests and login-route scope.
