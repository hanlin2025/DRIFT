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

Both the importer overview and the freight-forwarder portfolio load `GET /api/shipments` for the signed-in company and show the result in one table: Reference, Origin, Destination, Transshipment port, Mother vessel, Feeder vessel, and Planned arrival (the mother vessel's planned arrival, in the browser's locale). The table keeps the API order, which is newest `createdAt` first. The page says it is loading while the request is in progress. A failure shows the error and **Try again**. A successful empty response says the company has no shipments yet. A 401 ends the session and returns to `/login`.

The registration form stays on the freight-forwarder page. It collects the required transshipment port as free text, up to 200 characters, and sends that value with the rest of the itinerary. After a shipment is saved, the list reloads. The company scope comes from the authenticated API; the page does not offer a company picker.

The reference in that table opens `/importer/shipments/:id` or `/freight-forwarder/shipments/:id`, loaded from `GET /api/shipments/{id}`. The detail page shows the planned connection window returned with the shipment: the time available between the mother-vessel arrival and the feeder-vessel departure. The table itself does not show that duration. A shipment of another company returns 404, so it is reported as not found.

## Browser tests

With the backend running on port 8080 against the default `drift` database, and Mailpit
on port 8025 (`docker compose up -d postgres mailpit`):

```bash
npx playwright install chromium
npm run test:e2e
```

That command covers desktop and mobile signup, login, password reset, role routing,
validation, signed-out redirects, session expiry, and invitation reuse against the real API.
It does not run the
shipment retrieval spec below. Set `DRIFT_E2E_DATABASE` only when the backend uses that same
database.

### Shipment retrieval

`e2e/shipment-retrieval.spec.ts` signs in a disposable freight forwarder, confirms Harbourline
has no shipments, submits one shipment through the form, and checks the 201 response, the
automatic list refresh, the six displayed fields, and the same row after reload. It uses the
real shipment API.

From `frontend/`, with PostgreSQL accepting connections and port 8080 free:

```bash
npx playwright install chromium
npm run test:e2e:shipment
```

That command runs `scripts/run-shipment-retrieval.mjs`. The runner generates a database name,
creates that database, and records ownership only after creation succeeds. It then starts a
backend with `SPRING_DATASOURCE_URL` and `SPRING_FLYWAY_URL` set to that database, so Flyway
migrates only the database it just created. The spec receives the same name and writes fixtures
only when the database comment matches the ownership record. A supplied name is not enough.
`AIS_ENABLED` is false, and the JWT secret stays in the backend process.

If port 8080 is already in use, the runner stops before creating a database and does not stop
the other process. After the spec, a failed spec, or a backend that exits before it is ready,
the runner stops only the backend it started and drops only the database it created. It does
not drop `drift`, `drift_test`, or a database that already existed. Cleanup is not guaranteed
if the runner is killed in a way it cannot catch, or if a second interrupt arrives during
cleanup. The runner reports a drop or shutdown failure instead of ignoring it.

Running the spec file directly, including `npx playwright test` with only `DRIFT_E2E_DATABASE`
set, stops before any fixture write. `npm run test:e2e` still runs the signup and login specs
and does not run this one.

Ordering and company isolation stay in `ShipmentCreationIntegrationTests`. This spec does not
open a shipment detail page.
