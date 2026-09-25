# DRIFT Frontend

React + TypeScript, built with Vite. Requires Node.js 22.12+ (Node 24 LTS recommended).

```bash
npm ci
npm run dev
```

Open http://localhost:5173/signup. Start the Spring Boot backend on port 8080 first.
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
