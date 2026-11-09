# First administrator (CDG-309)

A fresh installation has no administrator. Signup stays invitation-only. The operator creates exactly one initial administrator, then that person signs in and invites colleagues.

## Who may bootstrap

Only the operator who can set the deployment environment. DRIFT does not ship a credential, and the setup page does not store one. A person who can only open the website cannot create an administrator.

## One-time credential

Set `DRIFT_SETUP_TOKEN` in the environment of the backend process before the first start. Use at least 32 bytes, and do not commit the value. The same terminal still needs `JWT_SECRET`.

```bash
export JWT_SECRET='replace-with-a-local-secret-at-least-32-bytes'
export DRIFT_SETUP_TOKEN='replace-with-a-different-setup-secret-at-least-32-bytes'
cd backend
bash ./mvnw spring-boot:run
```

Leave `DRIFT_SETUP_TOKEN` unset, or shorter than 32 bytes, and setup stays closed. Requests then return 404 and create nobody.

Unset the variable after the administrator exists. A later process without it cannot run setup again, and a process that still has it is rejected once the installation is initialized.

## Create the administrator

Open http://localhost:5173/setup. Enter the setup credential. Choose an active organisation, then the administrator's name, email and a password that meets the existing policy. The credential is sent in the `X-DRIFT-Setup-Token` header. It is not placed in the URL, the JSON body, the response, or the application log.

The account can sign in at http://localhost:5173/login. The response contains the administrator and organisation only.

## What cannot create a second administrator

- A missing or wrong credential returns 401 and creates nobody.
- An installation that already has an administrator, or a completed setup row, returns 409.
- Two overlapping attempts leave one administrator. The database lock and the single setup row decide the winner.
- An inactive or unknown organisation, or a password that fails the existing policy, returns 400 and creates nobody.
- `POST /api/register` still requires an invitation. It cannot grant the administrator role.

## Tests

`bash scripts/test-backend.sh` runs the API tests against `drift_test`. They cover the credential, a closed deployment, one successful administrator, replay, an existing administrator, validation, and overlapping attempts.

In `frontend/`, `npm test` covers the form states. `node ../scripts/run-first-admin-setup.mjs` runs the browser test against a new database and leaves that database in place.
