# Company-linked registration (CDG-17)

Registration is invitation-only. The backend assigns company and role from the invitation;
values submitted by the browser cannot grant membership or change the role.

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

On Windows, use `python` instead of `python3`. The script uses the repository's Docker
PostgreSQL container and prints a private, seven-day signup link. It does not send email.
Reissuing an invitation revokes any previous pending invitation for that email. This is a
local provisioning tool, not a public invitation API. Only authorised administrators should
provision invitations in a deployed system and deliver links to the intended recipient.

No fixed invitation tokens or account passwords are checked into the repository.
Only SHA-256 token hashes are stored. Invalid, expired, revoked, consumed invitations and
inactive companies are rejected. Creating a user and consuming the locked invitation occur
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
