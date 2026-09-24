# DRIFT

DRIFT is a transshipment connection risk engine. This repository is a monorepo containing the backend and frontend.

## Prerequisites

Before you run the project, install:

- **Git**
- **JDK 25** (required by `backend/pom.xml`)
- **Docker Desktop** (includes Docker Compose)
- Optional: IntelliJ IDEA or VS Code

You do **not** need to install Maven or PostgreSQL separately. The project uses the Maven Wrapper and Docker Compose.

## Project Structure

```
DRIFT/
├── backend/                 # Spring Boot backend
│   ├── mvnw                 # Maven Wrapper (Mac/Linux)
│   ├── mvnw.cmd             # Maven Wrapper (Windows)
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/    # Flyway migrations go here
├── frontend/                # Placeholder for now
├── docker-compose.yml       # Local PostgreSQL
└── README.md
```

## Local Setup

1. Clone the repository:

   ```bash
   git clone <repository-url>
   cd DRIFT
   ```

2. Start PostgreSQL with Docker:

   ```bash
   docker compose up -d
   ```

   This starts a PostgreSQL container with:
   - Database: `drift`
   - Username: `drift`
   - Password: `drift`
   - Port: `5432`

3. Run backend tests:

   ```bash
   cd backend
   ./mvnw test
   ```

   On Windows, use:

   ```bash
   mvnw.cmd test
   ```

4. Run the backend:

   ```bash
   ./mvnw spring-boot:run
   ```

   On Windows:

   ```bash
   mvnw.cmd spring-boot:run
   ```

   The backend starts on `http://localhost:8080`.

5. Stop PostgreSQL when you are done:

   ```bash
   docker compose down
   ```

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

- **`Failed to determine a suitable driver class`**  
  Make sure PostgreSQL is running: `docker compose up -d`.  
  Check that `backend/src/main/resources/application.yml` has the correct datasource configuration.

- **`./mvnw: Permission denied`** (Mac/Linux)  
  Run `chmod +x mvnw` in the `backend/` directory.

- **Windows PowerShell does not recognize `./mvnw`**  
  Use `mvnw.cmd` instead, or run the command from Git Bash.