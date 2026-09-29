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
web server to return `index.html` for client routes such as `/signup`, `/login` and
`/importer/shipments/:id`.
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

Both workspaces list the shipments registered for the signed-in user's company, newest
first, from `GET /api/shipments`. Each row shows the shipment reference, origin and
destination, mother and feeder vessels, and the planned mother-vessel arrival. The list
is loaded from the API each time the workspace opens, so it survives a browser refresh,
and it reloads after the freight-forwarder form registers a shipment. With no shipments,
an empty state is shown; the freight-forwarder one links to the registration form.

Selecting a shipment opens `/importer/shipments/:id` or `/freight-forwarder/shipments/:id`,
loaded from `GET /api/shipments/{id}`. A shipment of another company returns 404, so it is
reported as not found.

## Browser tests

With the backend running on port 8080 against the default `drift` database:

```bash
npx playwright install chromium
npm run test:e2e
```

Tests create and remove uniquely named fixtures in that database and cover desktop/mobile
signup, login, role routing, validation, signed-out redirects, session expiry and invitation
reuse against the real API. Set
`DRIFT_E2E_DATABASE` to override the fixture database; the backend must use the same one.
