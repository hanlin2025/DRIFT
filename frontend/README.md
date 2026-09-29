# DRIFT Frontend

React + TypeScript, built with Vite. Requires Node.js 22.12+ (Node 24 LTS recommended).

Run these commands **inside `frontend/`**, using the same OS as your repository.
For this WSL checkout, `node -p 'process.platform'` must print `linux`; Windows npm
will fail with `UNC paths are not supported`. Follow the WSL runtime setup in the
[root README](../README.md) if needed.

```bash
node -p 'process.platform'
npm ci
npm run dev
```

Open http://localhost:5173/signup. Start the Spring Boot backend on port 8080 first,
using the default `drift` database as described in the root README. Keep both terminals open.
If port 5173 is occupied, stop the previous frontend process before starting another.
Vite forwards `/api` requests to that backend; no broad CORS permissions are required.
Use the invitation provisioning instructions in [registration setup](../docs/registration.md)
to obtain a link with the assigned company, email and role.

```bash
npm run build
npm test
```

For deployment, serve the frontend and `/api` under the same origin and configure the
web server to return `index.html` for client routes such as `/signup` and `/login`.
`vite preview` previews static assets only; it is not the production API gateway.

## Signup and login routing

An invitation link opens `/signup#invite=...`; its fragment is never sent as part of the
HTTP URL. Company, email and role are read-only. The form validates names, passwords and
confirmation, surfaces backend errors and prevents repeated submissions while pending.
A successful 201 response replaces the signup history entry with `/login`. No password
or invitation is passed to the login route. Failed requests stay on signup.

After registration, the login form accepts the work email and password. A successful
login stores the session in the browser tab and opens the importer shipment overview
or the freight-forwarder shipment portfolio. An expired or rejected session returns
to `/login`. Signing out clears that session.

## Company shipments

Both the importer overview and the freight-forwarder portfolio load `GET /api/shipments` for the signed-in company and show the result in one table: Reference, Origin, Destination, Transshipment port, Mother vessel, and Feeder vessel. The table keeps the API order, which is newest `createdAt` first. The page says it is loading while the request is in progress. A failure shows the error and **Try again**. A successful empty response says the company has no shipments yet. A 401 ends the session and returns to `/login`.

The registration form stays on the freight-forwarder page. It collects the required transshipment port as free text, up to 200 characters, and sends that value with the rest of the itinerary. After a shipment is saved, the list reloads. The company scope comes from the authenticated API; the page does not offer a company picker.

These review requests overlap CDG-58, which was opened to integrate the dashboard with the shipment API. The list, loading, errors, empty company, and session expiry on fetch now live in this page so the team can reconcile that ticket’s ownership.

The page does not open a shipment detail route. Browser end-to-end tests still write fixtures to the configured database, so they are not a substitute for the mocked workspace tests.

## Browser tests

With the backend running on port 8080 against the default `drift` database:

```bash
npx playwright install chromium
npm run test:e2e
```

That command covers desktop and mobile signup, login, role routing, validation, signed-out
redirects, session expiry, and invitation reuse against the real API. It does not run the
shipment retrieval spec below. Set `DRIFT_E2E_DATABASE` only when the backend uses that same
database.

### Shipment retrieval

`e2e/shipment-retrieval.spec.ts` signs in a disposable freight forwarder, confirms Harbourline
has no shipments, submits one shipment through the form, and checks the 201 response, the
automatic list refresh, the six displayed fields, and the same row after reload. It uses the
real shipment API. It refuses `drift`, `drift_test`, and an unset database.

Choose a new database name. From the repository root, after PostgreSQL is accepting connections, continue only when the name query prints nothing:

```bash
export DRIFT_E2E_DATABASE=drift_cdg59_your_suffix
docker compose exec -T postgres psql -U drift -d postgres -Atc "SELECT datname FROM pg_database WHERE datname = '${DRIFT_E2E_DATABASE}'"
docker compose exec -T postgres psql -U drift -d postgres -c "CREATE DATABASE ${DRIFT_E2E_DATABASE}"
export JWT_SECRET='replace-with-a-local-secret-at-least-32-bytes'
export AIS_ENABLED=false
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/${DRIFT_E2E_DATABASE}"
cd backend && bash ./mvnw spring-boot:run
```

Flyway migrates that database on startup. In `frontend/`, export the same database name and run:

```bash
export DRIFT_E2E_DATABASE=drift_cdg59_your_suffix
npx playwright test --grep @disposable-database --project=desktop
```

Ordering and company isolation stay in `ShipmentCreationIntegrationTests`. This spec does not
open a shipment detail page.
