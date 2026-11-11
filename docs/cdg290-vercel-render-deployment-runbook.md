# CDG-290 hosted presentation deployment runbook

## Status and architecture

This package is configuration for review. It does not create hosting resources or authorize a deployment. The proposed path is:

```text
Browser → Vercel React/Vite static app → same-origin /api rewrite → Render Spring Boot web service → private Render PostgreSQL 16
```

Vercel forwards `/api` to the Render service and leaves the browser URL unchanged. API responses are marked `no-store`; all other routes fall back to the Vite `index.html`. Render runs one backend instance because controlled scenario sessions are held in process memory. PostgreSQL accepts private network connections only. Render health checks call `/readyz`; `/livez` is also public and returns status only.

The Render service has no automatic deploy and Render preview creation is manual. Vercel builds fail unless `DRIFT_VERCEL_DEPLOY_APPROVED=true` is deliberately set in the Vercel project. Do not set that variable until a reviewer authorizes deployment and the MapLibre security blocker is resolved. The default rewrite target assumes Render's service domain is `drift-cs203-presentation-api.onrender.com`; if Render assigns another domain, change both API rewrite destinations before authorizing a Vercel build.

## Local disposable rehearsal

Requirements: Docker with daemon access, Node 22.12 or newer, npm, Python 3, and curl. The smoke script builds the same Dockerfile used by Render, starts PostgreSQL 16 on a temporary Docker network and tmpfs, applies migrations through V34, performs authenticated registration/login/profile and shipment POSTs through a local Vercel-rewrite contract harness, restarts with scenario bindings, checks the scenario lifecycle, and removes the database, network, and image on exit.

```bash
cd DRIFT
./scripts/test-cdg290-deployment-readiness.sh
```

The smoke test creates only fictional users/shipments in a disposable local database. It leaves no named database volume. Set `DRIFT_READINESS_API_PORT` if port 18080 is occupied. The local proxy harness verifies the Vercel routing contract; it does not substitute for a real Vercel deployment test.

Build or run the services separately:

```bash
# Production backend image (Docker build context is repository root)
docker build -f backend/Dockerfile -t drift-backend:local .

# Frontend production build
cd frontend
npm ci
VITE_CONTROLLED_SCENARIO_ENABLED=true \
VITE_SCENARIO_AI_ENABLED=true \
npm run build
```

For a local PostgreSQL backend process, set `SPRING_PROFILES_ACTIVE=production`, `PORT=8080`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and a high-entropy `JWT_SECRET`, then run `java -jar backend/target/backend-0.0.1-SNAPSHOT.jar`. The Docker image entrypoint activates the production profile and binds to Render's injected `PORT` value.

## Render configuration

`render.yaml` is a review-only Blueprint. It describes a Singapore-region Spring service on one `0.5c-512mb` instance and a PostgreSQL 16 database on `0.5c-1g`, with an empty public IP allowlist. Applying the Blueprint creates paid hosted resources and is outside the authorization for this change. Confirm current pricing and retention before anyone applies it.

When provisioning is separately approved:

1. Create or apply the Blueprint in a dedicated DRIFT demo workspace; do not attach a production/shared database.
2. Confirm the database is PostgreSQL 16, in the same region as the backend, with no public IP allowlist entries. Connect the backend using the Render **internal** hostname, never the external database address.
3. Set the required backend secrets in Render's secret environment editor. Do not put them in the Blueprint, Vercel variables, source control, shell history, or PR comments.
4. Use separate database principals if available: runtime needs only application DML; Flyway needs schema/migration rights. `FLYWAY_URL`, `FLYWAY_USER`, and `FLYWAY_PASSWORD` override the datasource credentials for migrations. If separate roles are not provisioned, the configured fallback uses datasource credentials for Flyway; record that as a temporary least-privilege limitation.
5. Before startup against any persistent database, verify the database is dedicated to this demo and take an approved backup. Hibernate is `validate`; Flyway validates history, rejects baselining and out-of-order migrations, and disables `clean`. Startup applies pending migrations before readiness. Check `flyway_schema_history` ends at V34 before presenting.
6. Leave the scenario, AIS, recorded/curated playback and AI flags disabled until the synthetic seed is complete. `render.yaml` does not set provider keys, and external AI requests require the existing explicit approval gates.
7. Keep the backend at exactly one instance. Do not enable autoscaling or horizontal replicas while sessions remain process-local.

Safe variable template (replace placeholders in the host secret editor; values below are not credentials):

```dotenv
SPRING_PROFILES_ACTIVE=production
PORT=<Render-injected-port>
SPRING_DATASOURCE_URL=jdbc:postgresql://<private-host>:<port>/<database>?sslmode=require
SPRING_DATASOURCE_USERNAME=<demo-runtime-user>
SPRING_DATASOURCE_PASSWORD=<secret-from-password-manager>
FLYWAY_URL=jdbc:postgresql://<private-host>:<port>/<database>?sslmode=require
FLYWAY_USER=<demo-migration-user>
FLYWAY_PASSWORD=<secret-from-password-manager>
JWT_SECRET=<at-least-256-bits-random-secret>
AIS_ENABLED=false
MARITIME_CONTEXT_CONTROLLED_REPLAY_ENABLED=false
CONTROLLED_SCENARIO_ENABLED=false
MARITIME_AI_ENABLED=false
MARITIME_AI_SCENARIO_ENABLED=false
MARITIME_AI_CONTROLLED_REPLAY_STUB_ENABLED=false
MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=false
```

Use the provider's internal DB connection details and its recommended TLS mode/CA. Do not paste a credential-bearing database URL into a terminal argument or ticket. `PORT` is supplied by Render; the placeholder above is descriptive and should not be manually replaced unless Render's platform requires it.

## Synthetic demo account and two shipments

Use only fictional email addresses and a password-manager generated demo password. The migrations seed two separate synthetic companies: `HARBOURLINE_DEMO` and `STRAITS_FRESH_DEMO`.

1. With scenario disabled, create a one-time `FREIGHT_FORWARDER` invitation for a new email in `HARBOURLINE_DEMO`. Use the existing invitation table and the operator's private admin `psql` connection. Generate a token on a trusted workstation and store only its SHA-256 hash in the database:

   ```bash
   python3 - <<'PY'
   import hashlib, secrets
   token = secrets.token_urlsafe(32)
   print("Invite token (keep private):", token)
   print("Invite hash for SQL:", hashlib.sha256(token.encode()).hexdigest())
   PY
   ```

   From a trusted SQL console or one-off operator process attached to the **private** Render network (keep the database allowlist empty), run this with `DEMO_EMAIL` and `DEMO_TOKEN_HASH` in the current shell. The database connection password must come from the platform secret store/`PGPASSFILE`, not from a command argument:

   ```bash
   psql -X -v ON_ERROR_STOP=1 \
     -v demo_email="$DEMO_EMAIL" \
     -v demo_token_hash="$DEMO_TOKEN_HASH" <<'SQL'
   BEGIN;
   SELECT id AS company_id FROM companies
     WHERE code = 'HARBOURLINE_DEMO' AND active = TRUE \gset
   UPDATE invitations SET status = 'REVOKED'
     WHERE email = lower(trim(:'demo_email')) AND status = 'PENDING';
   INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
   VALUES (lower(trim(:'demo_email')), :company_id, 'FREIGHT_FORWARDER',
           :'demo_token_hash', NOW() + INTERVAL '1 hour');
   COMMIT;
   SQL
   ```

   Confirm the SQL console is connected to the dedicated demo database and that the company query returned one row. Deliver the token only to the presenter through a private channel. No email is sent by this application.

2. Open the approved Vercel URL and complete signup from `/signup#invite=<private-token>`. Sign in and check `/api/session` returns the expected synthetic company and `FREIGHT_FORWARDER` role. Do not put the password in a Vercel environment variable.

3. Using Shipment Details' existing create-shipment flow as that user, add two distinct shipments, both in `HARBOURLINE_DEMO`, with references `CS203-DEMO-A` and `CS203-DEMO-B`. Mark both active (new shipments are active). Use fictional origin/destination, vessel labels, Singapore transfer port, and a valid mother-arrival/feeder-departure window. Leave IMO/MMSI empty; do not attach real AIS observations. Check each shipment is visible to the authorized user and record its database ID from the API response or a read-only admin query.

4. Record the demo user's numeric ID, company ID and the two distinct active shipment IDs. Confirm ownership/company visibility and confirm A/B are not archived. In Render, set `CONTROLLED_SCENARIO_DEMO_COMPANY_ID`, `CONTROLLED_SCENARIO_ALLOWED_USER_IDS`, `CONTROLLED_SCENARIO_SHIPMENT_A_ID`, and `CONTROLLED_SCENARIO_SHIPMENT_B_ID` to those IDs, then set `CONTROLLED_SCENARIO_ENABLED=true`. Restart the single backend instance and verify ready. The session API rechecks user, company, role, and shipment visibility; these environment variables do not bypass authorization.

5. Preserve the AIS/recorded-playback flags as false. For an offline grounded-assessment rehearsal, use the existing reviewed local stub only in an isolated test environment. The hosted demo should show an honest unavailable/abstention state unless there is a separate explicit approval to use the real provider. Never claim a stub or replay is a live model response.

## Vercel setup

1. Create a Vercel project with the repository's `frontend` directory as the Root Directory and Node 22.12+.
2. Keep the configured install/build/output settings from `frontend/vercel.json`: `npm ci`, `npm run build`, `dist`.
3. In the dedicated presentation project, set the nonsecret `VITE_CONTROLLED_SCENARIO_ENABLED=true` and `VITE_SCENARIO_AI_ENABLED=true` variables in the **Production** scope only. Keep them absent/false for previews. They only reveal frontend affordances; backend authorization and provider gates remain authoritative.
4. Keep the Vercel deployment approval variable unset while preview or production deployment is unauthorized. The build then exits before generating artifacts. After security/reviewer approval and separate deployment authorization, set `DRIFT_VERCEL_DEPLOY_APPROVED=true` only in the intended Vercel environment.
5. Verify the Render domain in `frontend/vercel.json`, then check both exact `/api` and nested `/api/:path*` rewrites still precede the final SPA fallback. Do not configure bearer tokens or database/provider secrets in Vercel.
6. Once deployment is explicitly authorized, test POST login/register, GET `/api/session` with `Authorization: Bearer <token>`, and a refreshed deep link such as `/shipments/<id>` through the actual Vercel hostname. Confirm API responses remain no-store.

## Rehearsal and rollback

- Before the presentation, check Render `/livez` and `/readyz`; the readiness payload should reveal only `status`, not database details. Check V34 and the configured single instance in the Render dashboard.
- Log in as the synthetic forwarder and rehearse each shipment independently. On Shipment Details A, Start and advance to CHANGE (no email), then advance to WORSENED and verify one A-only synthetic email. Repeat separately on Shipment Details B and verify one B-only email only when B reaches WORSENED. Confirm that refreshing or repeating a request at WORSENED does not send another email, then Reset each scenario. Rehearse a direct refresh on Shipment Details and verify scenario state is cleared after a backend restart (process-local sessions are intentionally ephemeral).
- Verify the map fallback is legible with external tile requests blocked. Map tile availability is independent of scenario playback and AIS provenance.
- Verify AI states from the returned persisted assessment and its `GENERATED` / `VALID` status, provenance, model/provider metadata and request ID. A UI stub, captured response or replay is not a genuine model response. Do not call a paid provider without a separate fresh authorization.
- On failure, use Vercel's prior successful deployment only after authorized release; use Render's prior image/deploy for backend rollback. Check application compatibility with the current database schema before rolling back. Never automatically roll back or edit migration history. Restore a database backup only under the designated data owner's direction.

## Blockers before public use

- **CDG-290 / security owner:** current `maplibre-gl@5.24.0` is recorded in the integration candidate as affected by a critical advisory. The attempted 6.4.1 upgrade caused broad test failures. This package intentionally leaves that dependency and pending #241 map-readiness guard untouched. Keep the application unexposed publicly until the owner supplies a tested remediation.
- **CDG-290 / GitHub Implementer:** integrate this review package only after dependency-safe review against #232 and the pending #241/#244 sequence. This branch is based on #232 and must be refreshed after the coordinator advances that candidate.
- **Deployment owner:** no Vercel project, Render service, or hosted database was created or tested. Domain assignment, region availability, account plan, current pricing, TLS mode, hosted migration, real-edge rewrite, browser/WebGL, secret-store and rollback behavior still require the approved hosted environment.
- **AI owner / presenter:** genuine provider acceptance is not proven by offline fixtures or stubs. Provider use remains off and requires separate explicit authorization.
