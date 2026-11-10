# Company-linked registration (CDG-17)

Registration is invitation-only. The backend assigns company and role from the invitation;
values submitted by the browser cannot grant membership or change the role. The first
administrator is created separately by an operator. See [first administrator setup](first-administrator.md).

## Local setup

Start PostgreSQL with `docker compose up -d`, then set `JWT_SECRET` to a private
value of at least 32 bytes and run `bash mvnw spring-boot:run` in `backend/`
(Windows: `mvnw.cmd spring-boot:run`). The backend does not start without that
secret. Flyway creates these demo companies:

| Company code | Company name |
| --- | --- |
| HARBOURLINE_DEMO | Harbourline Logistics (Demo) |
| STRAITS_FRESH_DEMO | Straits Fresh Imports (Demo) |

After the backend has applied migrations, run from the repository root:

```bash
python3 scripts/create-invitation.py --email alice@example.com --company HARBOURLINE_DEMO --role FREIGHT_FORWARDER
```

`--company` accepts any active organisation code, including one created through the admin
workspace. The script rejects a missing or inactive code before writing an invitation.

On Windows, use `python` instead of `python3`. The script uses the repository's Docker
PostgreSQL container and prints a private, seven-day signup link. It does not send email.
Reissuing an invitation revokes any previous pending invitation for that email. This is a
local provisioning tool, not a public invitation API. Only authorised administrators should
provision invitations in a deployed system and deliver links to the intended recipient.

No fixed invitation tokens or account passwords are checked into the repository.
Only SHA-256 token hashes are stored. Invalid, expired, revoked, consumed invitations and
inactive companies are rejected. A revoked invitation tells the visitor that it is no longer
valid, and does not say whether an account exists. Creating a user and consuming the locked invitation occur
in one database transaction. An unsuccessful registration does not consume the invitation.

## API

- `POST /api/invitations/resolve`: JSON `{ "token": "..." }`; returns invited email,
  company name, role and expiry. Uses a request body to avoid tokens in API URLs.
- `POST /api/register`: JSON `{ "fullName": "Alice Tan", "email": "alice@example.com",
  "password": "...", "invitationToken": "..." }`; returns 201 and the account summary.
- Invalid input/invitation: 400 with `message` and field `errors`; duplicate account: 409.
- Passwords follow the existing policy: at least eight Unicode code points, uppercase,
  lowercase and a digit, no whitespace, and at most 72 UTF-8 bytes for BCrypt.

Company membership does not grant access to all shipments. Shipment authorisation belongs
to the shipment endpoints. Existing accounts have no company assigned automatically; their
membership must be verified before access to company data is granted.

## Tests

Run `bash scripts/test-backend.sh` from the repository root (WSL/Git Bash). It starts
PostgreSQL, waits for readiness, creates `drift_test` if missing, and runs Maven tests.
Tests use that separate database; they do not insert test accounts into `drift`.
The integration suite covers validation, invitation eligibility, membership tampering,
password hashing, duplicate accounts and concurrent claims of one invitation.

## Existing development databases

The application and Docker both use `drift` by default. Start the backend normally with
`bash mvnw spring-boot:run` in `backend/`; no datasource environment variable is needed.
`JWT_SECRET` is still required, as described above.

Earlier branches rewrote V1. If your existing local `drift` database reports a Flyway
checksum mismatch and its data is disposable, stop the backend and run these commands
from the repository root. **This deletes all data in `drift`**, but leaves other databases
and the Docker volume in place:

```bash
docker compose exec -T postgres dropdb -U drift drift
docker compose exec -T postgres createdb -U drift drift
```

Start the backend again to apply the migrations and preset company records, then create
new local invitations with the invitation helper. Do not reset a database whose data you
need to keep; that database requires a deliberate schema reconciliation instead.

## Company self-service signup (CDG-360)

Besides joining through an invitation, anyone can create a new company and become its first
administrator with `POST /api/register/company` (public). The request and response shapes are
defined in [`cdg360-signup-contract.md`](cdg360-signup-contract.md); that file is the source of truth.

- One transaction creates an **inactive** company, an `ADMIN` account with status
  `PENDING_VERIFICATION`, an email verification token (SHA-256 hash only, table
  `email_verification_tokens`) and a `REGISTERED` row in `company_signup_audits` (migration V39).
- The response is always `202` with the same body, including when the email is already registered
  (nothing is created in that case). A taken company code returns `409` with `errors.companyCode`.
- A pending account cannot sign in: the correct password returns `403` with a "verify your email"
  message and no session; a wrong password stays `401`. Any session whose account is not `ACTIVE` is
  rejected with `401`. Admins cannot remove or reactivate a pending account (`409`).
- While inactive, the company is not offered for invitations or shipment linking.
- The verification email is sent **after** the signup commits, with a link of the form
  `{app.signup.link-base-url}#token=<43 characters>`. A delivery failure is logged without the
  token and does not undo the signup; the administrator can request a new link.
- `POST /api/auth/verify-email` uses up the link, activates the account and the company, and
  records `EMAIL_VERIFIED`. A used, expired, replaced or unknown link gets the same `400`.
- `POST /api/auth/resend-verification` replaces any unused link of a pending account and emails a
  new one. The `200` body is the same for every email. Repeating a pending signup with the same
  company code also sends a fresh link.

### Signup settings

| Property | Environment variable | Default |
|---|---|---|
| `app.signup.link-base-url` | `SIGNUP_LINK_BASE_URL` | `${app.frontend-base-url}/verify-email` (`http://localhost:5173/verify-email`) |
| `app.signup.verification-ttl` | `SIGNUP_VERIFICATION_TTL` | `PT24H` |
| `app.signup.from` | (uses `MAIL_FROM_ADDRESS`) | `${app.mail.from}` |
| `app.signup.rate-limit.ip` | `SIGNUP_IP_LIMIT` | `5` per window |
| `app.signup.rate-limit.email` | `SIGNUP_EMAIL_LIMIT` | `3` per window |
| `app.signup.rate-limit.window` | `SIGNUP_RATE_WINDOW` | `PT1H` |

Signup, verification and resend each have their own buckets. Signup and resend are limited per
client address and per email, verification per client address. A limited request gets
`429 {"message":"Too many requests. Try again later.","errors":{}}` whether or not the email is
registered. The limiter is in memory, so limits reset when the backend restarts and are not shared
between instances. In production, `ProductionMailConfigurationGuard` requires a hosted HTTPS
`SIGNUP_LINK_BASE_URL` on the `FRONTEND_BASE_URL` host and a non-local sender.

### Reading verification mail locally

With the default settings the backend sends mail to Mailpit from `docker-compose.yml`
(SMTP `localhost:1025`). Start it with `docker compose up -d mailpit` and open
<http://localhost:8025> to read the verification email and follow its link.
- The code lives in `com.drift.backend.account.signup`. The admin-only organisation creation
  (CDG-313), the invitation flow (`/api/register`) and the operator bootstrap (`/api/setup/*`) are
  unchanged.

Tests: `CompanySignupIntegrationTests`, `CompanySignupConcurrencyIntegrationTests`,
`EmailVerificationIntegrationTests` and `EmailVerificationRateLimitIntegrationTests`.
