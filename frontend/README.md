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

The reference in that table opens `/importer/shipments/:id` or `/freight-forwarder/shipments/:id`, loaded from `GET /api/shipments/{id}`. The detail page shows the planned connection window returned with the shipment: the time available between the mother-vessel arrival and the feeder-vessel departure. The table itself does not show that duration. A Risk badge uses the remaining connection window when the detail includes one, and otherwise the planned connection window. Critical when that window is missed or not positive, High under 12 hours, Medium under 24 hours, and Low at 24 hours or more. The explanation names which window was used, and it says the planned connection window is being used when the arrival estimate is unavailable or possibly outdated. It shows Pending assignment until the feeder departure is scheduled. While the shipment loads, the page shows a skeleton of the map and facts. The facts sit in two columns on wider screens and one column on phones. A shipment the user may not view returns 403 and shows an **Access denied** page with the server's message. An unknown or invalid shipment ID returns 404 and shows **Shipment not found** with "Shipment not found or has been removed." Neither offers a retry.

Next to the map, a Mother vessel panel shows the latest AIS fix returned by `GET /api/shipments/{id}/tracking`. It lists the reported name and MMSI, the position with hemispheres, the speed in knots, and the course and heading in degrees with a compass point. A field the vessel did not report shows "Not reported". "Updated 12 min ago" counts from the time DRIFT received the report; hover it for the exact time. A fix more than six hours old carries a **Stale** badge. A vessel with no retained report shows "No position received yet". The panel follows the page's 60-second tracking poll and the Refresh button, and the age advances every 30 seconds without a reload. On wide screens the panel sits beside the map; on narrower screens it moves below it. It is hidden with the rest of the page when the shipment is forbidden. ETA and alerts are not part of it.

Below the map, in the same column, the Track history section draws the recent path of the mother vessel from `GET /api/shipments/{id}/track-history`, oldest to newest, over the last 24 hours. The 6h, 24h and 72h buttons switch the window, and Mother vessel / Feeder vessel switch the vessel; the feeder button is disabled until a feeder is assigned. Segments where the reported speed at both ends is below the threshold the API returns (3 knots) are drawn thicker in orange as slowdowns, and the summary counts them. Clicking a point on the map, or choosing it under **All positions**, shows its time, speed and heading ("Not reported" when the vessel did not send it). Observations flagged as invalid positions never reach the page. Fewer than two positions in the window shows "Insufficient history to draw a track for this window." The API returns at most 500 positions; when it keeps only the newest, the section says so. The track reloads with the page's 60-second poll and the Refresh button. A failed load keeps the rest of the page and asks to try Refresh; a 403 shows **Access denied**. Custom date ranges, playback and exporting the track are not part of it.

## Profile

The workspace header and the administrator header open `/profile` for the signed-in user. The page loads `GET /api/profile`. Full name is editable. Email, company, and role are read-only, and a user with no company sees "No company assigned". The shipment-risk section stores whether those emails are on and the minimum level, one of LOW, MEDIUM, HIGH, or CRITICAL. HIGH includes HIGH and CRITICAL. The password section uses the same four rules as registration. Save sends `PATCH /api/profile` and leaves both password fields out when they are empty. A validation failure shows the API message and stays on the page. A successful password change ends the session and returns to login with "Your password was changed. Log in with the new password." The page does not edit the email address, offer a photo, or send a notification email.

## Browser tests

With the backend running on port 8080 against the default `drift` database:

```bash
npx playwright install chromium
npm run test:e2e
```

That command covers desktop and mobile signup, login, role routing, validation, signed-out
redirects, session expiry, and invitation reuse against the real API. It does not run the
shipment retrieval, map monitoring, or shipment detail specs below. Set `DRIFT_E2E_DATABASE` only when the
backend uses that same database.

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

### Map monitoring

`e2e/map-monitoring.spec.ts` signs in a disposable freight forwarder and opens one shipment
detail page. It checks that a fresh vessel observation draws the live marker and the
last-updated line, that an observation older than six hours keeps that marker and shows the
last-known-position warning, and that a shipment with no observation shows the no-position
warning with no live marker. The same three cases check the Mother vessel panel: its position,
speed, course, heading and age, its Stale badge, and "No position received yet". A freight forwarder from another company receives 403 from the
shipment detail API and 404 from the tracking API, and the page shows that access alert
instead of the globe. A request with no session receives 401. Dragging the globe and zooming
with Ctrl plus the wheel move the marker without leaving the shipment URL.

From `frontend/`, with PostgreSQL accepting connections and port 8080 free:

```bash
npx playwright install chromium
npm run test:e2e:map
```

That command runs `scripts/run-map-monitoring.mjs`. The runner generates a database name,
creates that database, and records ownership only after creation succeeds. It then starts a
backend with `SPRING_DATASOURCE_URL` and `SPRING_FLYWAY_URL` set to that database, so Flyway
migrates only the database it just created. The spec receives the same name and writes fixtures
only when the database comment matches the ownership record. A supplied name is not enough.
`AIS_ENABLED` is false, and the JWT secret stays in the backend process. SQL goes through
Docker Compose when it is available, and through `psql` on `127.0.0.1` otherwise.

If port 8080 is already in use, the runner stops before creating a database and does not stop
the other process. After the spec, a failed spec, or a backend that exits before it is ready,
the runner stops only the backend it started and drops only the database it created. It does
not drop `drift`, `drift_test`, or a database that already existed. Cleanup is not guaranteed
if the runner is killed in a way it cannot catch, or if a second interrupt arrives during
cleanup. The runner reports a drop or shutdown failure instead of ignoring it.

Running the spec file directly, including `npx playwright test` with only `DRIFT_E2E_DATABASE`
set, stops before any fixture write. `npm run test:e2e` does not run this spec. No production
code or Flyway version changes with it.

### Shipment CSV import

`e2e/shipment-import.spec.ts` covers the two cross-layer CDG-26 journeys: a freight forwarder
downloads the canonical template, imports shipments through the page, sees the completed job in
the real portfolio and detail view, and an independently authenticated linked importer can list,
read, and track the linked shipment but cannot update it. The second journey imports one valid
and one invalid row, displays the API's persisted row errors, and downloads the browser-generated
CSV error report. CSV escaping for comma, quote, and newline values remains unit-covered because
the backend's production row-error messages deliberately contain no user-supplied text.

From `frontend/`, with PostgreSQL accepting connections and port 8080 free:

```bash
npx playwright install chromium
npm run test:e2e:import
```

That command runs `scripts/run-shipment-import.mjs` against a fresh, disposable
`drift_cdg130_*` database. It starts a normal Spring Boot process with the production async
executor (not the deterministic backend-test executor), migrates only that database, and passes
the database ownership token to the spec before it permits fixture writes. The runner stops only
the backend it starts and drops only the database it created; it never drops `drift`,
`drift_test`, or a pre-existing database. `npm run test:e2e` does not run this disposable spec.

### Shipment detail

`e2e/shipment-detail.spec.ts` opens a shipment from a disposable freight forwarder's list and
checks every field and the High risk badge for a nine-hour connection with no arrival estimate, before and after reload. The explanation says the planned connection window is being used.
A freight forwarder from another company does not get the shipment in the list and sees
**Access denied** at its address. An unknown or non-numeric ID returns 404 and shows **Shipment
not found**. A blank feeder vessel shows Pending assignment, and a vessel with no observation
shows No live AIS position. The live marker and the API's 403 and 401 stay in the map monitoring
spec.

From `frontend/`, with PostgreSQL accepting connections and port 8080 free:

```bash
npx playwright install chromium
npm run test:e2e:detail
```

That command runs `scripts/run-shipment-detail.mjs`, which creates, uses, and drops its own
`drift_cdg101_*` database the same way the map monitoring runner does. `npm run test:e2e` does
not run this spec.

### Profile

`e2e/profile.spec.ts` opens `/profile` from the signed-in workspace. A renamed user and a
CRITICAL notification choice are still there after sign-out and sign-in. Email, company, and
role stay read-only. The wrong current password and a password that misses the registration
rules are shown as the API message, and the old password still signs in. A compliant new
password returns to login, rejects the old password, and accepts the new one. An empty name
and a name over 200 characters are rejected without leaving the page. A second user sees only
their own profile, and a patch that names the first user returns 403.

From `frontend/`, with PostgreSQL accepting connections and port 8080 free:

```bash
npx playwright install chromium
npm run test:e2e:profile
```

That command runs `scripts/run-profile.mjs`, which creates, uses, and drops its own
`drift_cdg143_*` database the same way the shipment detail runner does. It covers desktop and
mobile. `npm run test:e2e` does not run this spec.

### Vessel details

`e2e/vessel-details.spec.ts` opens a shipment whose name, IMO, MMSI, operator, and planned
schedule were stored through the API. A freight forwarder and a linked importer see those
values on the detail page, including after reload. The create and list bodies do not include
the identity. An omitted IMO, MMSI, or operator is shown as Not available. A latest AIS name
that differs from the stored mother vessel shows "The AIS vessel name does not match the stored
mother vessel." A feeder name that matches after trim, collapsed whitespace, and case shows no
warning, and the stored MMSI stays the one on the shipment. A freight forwarder from another
company receives 403 "You do not have access to this shipment" from the detail API and 404
"Shipment not found" from tracking. Neither body contains the vessel details, and the page
shows the detail message without them. The detail record has no editor for these fields.

From `frontend/`, with PostgreSQL accepting connections and port 8080 free:

```bash
npx playwright install chromium
npm run test:e2e:vessel-details
```

That command runs `scripts/run-vessel-details.mjs`, which creates, uses, and drops its own
`drift_cdg147_*` database the same way the shipment detail runner does. It covers desktop and
mobile. `npm run test:e2e` does not run this spec.

### Arrival comparison

`e2e/arrival-comparison.spec.ts` opens a shipment whose arrival comparison comes from the live
detail API. The page shows the planned arrival beside the estimate, the limitation, the speed
in knots, the difference, and the last-received time. It covers an arrival ahead of plan, one
90 minutes later, one 6 hours later, and a position 7 hours old. Opening that first shipment
again leaves the estimated time unchanged. The planned connection window stays 6 hours. A fresh
estimate uses the remaining window for the risk badge: High while time remains under 12 hours, and
Critical when the estimated arrival is at the feeder departure. A position 7 hours old keeps High
from the planned window and says that window is being used.

From `frontend/`, with PostgreSQL accepting connections and port 8080 free:

```bash
npx playwright install chromium
npm run test:e2e:arrival-comparison
```

That command runs `scripts/run-arrival-comparison.mjs`, which creates and uses its own
`drift_cdg152_browser_*` database. SQL uses Docker Compose when it is available and `psql` on
`127.0.0.1` otherwise. The spec uses the path the runner selected. It covers desktop and mobile.
The runner leaves the database in place. `npm run test:e2e` does not run this spec.
