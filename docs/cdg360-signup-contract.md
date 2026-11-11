# CDG-360 API Contract (v0.3)

Status: **Agreed for implementation** (one ADMIN per company confirmed; open questions resolved in §9). Both developers (and their AI assistants) must treat this file as the single source of truth for request/response shapes. Change it only by agreement, and bump the version line.

Scope: CDG-361 (atomic company + first-admin registration), CDG-362 (signup choices UI), CDG-363 (email verification and abuse controls), CDG-364 / CDG-365 (self-assignment and last-admin safeguards), CDG-366 (end-to-end verification).

---

## 0. Conventions (copied from existing code, do not change)

- All error bodies follow the existing shapes:
  - Form-style endpoints (registration, password reset): `{"message": "...", "errors": {"<field>": "<message>"}}`. `errors` is `{}` when no single field is to blame.
  - Admin endpoints: `{"message": "..."}` only.
- Every response from the endpoints below sets `Cache-Control: no-store`.
- Emails are trimmed and lower-cased server-side before any check (same as `RegisterRequest`).
- Passwords follow the existing `PasswordPolicy` (8+ characters, upper, lower, digit, no spaces, max 72 UTF-8 bytes). Policy failures return `errors.password`.
- Organisation codes and names follow the existing `OrganisationIdentity` rules (code: letters/digits/underscores, starts with a letter, max 50, stored upper-case; name: 1-200 characters, no control characters).
- Tokens in emailed links are 43-character URL-safe strings delivered in the URL fragment (`#token=...`), and only their SHA-256 hash is stored (same as password reset and invitations).
- Rate-limited requests return `429` with `{"message": "Too many requests. Try again later.", "errors": {}}`.
- New public endpoints must be added to the `permitAll` list in `SecurityConfig`.
- OpenAPI annotations follow the style of `PasswordResetController`.

---

## 1. Create a company and its first administrator (CDG-361, CDG-363)

`POST /api/register/company` (public)

### Request

```json
{
  "companyCode": "PACIFIC_GATE",
  "companyName": "Pacific Gate Imports",
  "fullName": "Alex Tan",
  "email": "alex@pacificgate.example",
  "password": "Str0ngPass"
}
```

| Field | Rules | Error field |
|---|---|---|
| companyCode | Required; `OrganisationIdentity` code rules | `companyCode` |
| companyName | Required; `OrganisationIdentity` name rules | `companyName` |
| fullName | Required; max 200 | `fullName` |
| email | Required; valid email; max 320 | `email` |
| password | Required; `PasswordPolicy` | `password` |

### Server behaviour (one transaction)

Checks run in this order:

1. Validate fields: `400` on failure.
2. Rate limit (per client address and per email): `429`.
3. **Company code held by a company other than this email's own pending company**: `409` `errors.companyCode` (see below), **whether or not the email is registered**. Checking the code before the email means a taken code never reveals whether an email exists. Disclosing that an organisation code exists is acceptable because codes are not secret (see Q3). "This email's own pending company" is the inactive company whose `PENDING_VERIFICATION` admin has this email; repeating that code is not a conflict.
4. **Email is `ACTIVE` or `REMOVED`**: the same `202` as success. Nothing changes.
5. **Email is `PENDING_VERIFICATION`**: the newest submission **replaces** the pending one, within the per-email resend limit (§3), and the answer is the same `202`. In one transaction, with the pending user row and its company row locked: the user's full name and password hash and the inactive company's code and name are updated in place, every unused verification token is deleted, a new one is issued, a `RESUBMITTED` audit row is written, and the email is sent after commit. Links from earlier submissions stop working, so only someone who can read the mailbox can activate the latest submission (this also defeats pre-registration of someone else's email). Over the resend limit, nothing changes and the answer is still `202` (a `429` would reveal the email). If the company is not an inactive company with only this user, nothing changes (`202`). A code taken concurrently maps to `409` with everything rolled back.
6. Otherwise, create in **one transaction**:
   - `companies` row with `active = false` (so it does not appear in invitation or shipment-linking lookups until verified), `created_by_user_id` = the new user.
   - `users` row with role `ADMIN`, `status = PENDING_VERIFICATION`, linked to the new company.
   - `email_verification_tokens` row (hash only).
   - A `REGISTERED` row in `company_signup_audits` (see §7).
7. Send the verification email **after commit**. A delivery failure must not roll back the account. The user can use "resend" (§3). The response is still `202`.

If any step in 6 fails (including a unique-index violation from a concurrent request), nothing is persisted. A concurrent duplicate company code maps to `409`, and a concurrent duplicate email maps to the generic `202`.

### Responses

`202 Accepted`

```json
{"message": "Check your email to verify your account before signing in."}
```

`400 Bad Request` (validation)

```json
{"message": "Registration information is missing or invalid",
 "errors": {"companyCode": "Enter an organisation code using letters, digits, and underscores."}}
```

`400 Bad Request` (password policy)

```json
{"message": "Password must be at least 8 characters",
 "errors": {"password": "Password must be at least 8 characters"}}
```

`409 Conflict` (company code taken)

```json
{"message": "An organisation with this code already exists.",
 "errors": {"companyCode": "An organisation with this code already exists."}}
```

`429 Too Many Requests`: see conventions.

> The `202` body deliberately contains **no account id, email or role**, unlike `POST /api/register`.

---

## 2. Verify email (CDG-363)

Emailed link: `{app.signup.link-base-url}#token=<43-character token>` (frontend route suggested: `/verify-email`).

`POST /api/auth/verify-email` (public)

### Request

```json
{"token": "<43-character token>"}
```

### Server behaviour

- Look up by token hash. The token must be unused and unexpired (TTL: a defined threshold, suggested **24 hours**, config `app.signup.verification-ttl`).
- In one transaction: mark the token used, delete the user's other unused verification tokens, set the user to `ACTIVE`, and set the company to `active = true`.
- Replay of a used token, an expired token, a token replaced by a resend, or an unknown token all get the **same** `400`.

### Responses

`200 OK`

```json
{"message": "Your email has been verified. You can now sign in."}
```

`400 Bad Request`

```json
{"message": "This verification link is invalid or has expired. Please request a new one.",
 "errors": {"token": "This verification link is invalid or has expired. Please request a new one."}}
```

`429 Too Many Requests`: per client address.

---

## 3. Resend verification email (CDG-363)

`POST /api/auth/resend-verification` (public)

### Request

```json
{"email": "alex@pacificgate.example"}
```

### Behaviour

- If the email belongs to a `PENDING_VERIFICATION` account, issue a new token (earlier unused tokens are invalidated) and send it.
- In every other case (unknown email, already active, removed), do nothing.
- The response is identical in all cases.
- The email names the company (name and code) and the administrator's full name from the latest submission, and says: "If you did not request this, ignore this email; the account will not be activated."

### Responses

`200 OK`

```json
{"message": "If this email is awaiting verification, a new link has been sent."}
```

`400 Bad Request`: malformed email, same shape as the password-reset form.

`429 Too Many Requests`: per email and per client address.

---

## 4. Sign-in for unverified accounts (CDG-363)

`POST /api/login` (existing, behaviour change only)

- Wrong email or password: unchanged, `401 {"message": "Invalid email or password."}`.
- **Correct password** but the account is `PENDING_VERIFICATION`: `403`. No session cookie is issued.

```json
{"message": "Verify your email before signing in. You can request a new verification link.",
 "code": "EMAIL_NOT_VERIFIED"}
```

`code` is a stable, machine-readable marker: clients show the "verify your email" state only when `code` is `EMAIL_NOT_VERIFIED`, and treat any other `403` as a generic error. The message may change.

Rationale: the caller has already proven the password, so this does not enable email enumeration.

Any authenticated endpoint must also reject a `PENDING_VERIFICATION` account (defence in depth in `SessionAuthenticationFilter` / `AuthenticationService`), even though no session can be issued for it.

---

## 5. Join an existing company (CDG-362, existing API, no change)

The join path reuses the current invitation flow unchanged:

- `POST /api/invitations/resolve` `{"token": "..."}` returns `{email, companyName, role, expiresAt}`.
- `POST /api/register` `{fullName, email, password, invitationToken}`. The email must match the invitation, and the company and role come from the invitation.

Invited users are created `ACTIVE`. The invitation email has already proven ownership of the address, so no extra verification is needed.

Frontend entry: `/signup` shows two choices. "Join an existing company" opens the existing invitation flow (`#invite=<token>`). Without a token, it explains that an invitation from a company administrator is required.

---

## 6. Assignment safeguards (CDG-364, CDG-365)

`PATCH /api/admin/users/{userId}/assign` (existing) with body `{"organisationId": 2, "role": "LOGISTICS_MANAGER"}`.

New rules, checked **before** any change and before the audit record is written:

| Case | Status | Body |
|---|---|---|
| `userId` is the caller's own id (any role or organisation value, even unchanged) | `409` | `{"message": "You cannot change your own organisation or role."}` |
| Change would leave the target's company with no active ADMIN | `409` | `{"message": "A company must keep at least one active administrator."}` |
| Existing: second ADMIN in a company (DB trigger) | `409` | `{"message": "A company can have only one admin."}` (unchanged) |

Rejected requests leave the user row and `user_assignment_audits` unchanged.

`PATCH /api/admin/users/{userId}/remove` (existing): self-removal is already rejected with `400 "You cannot remove your own account."`. This rule is kept. Removal must also respect the last-active-admin rule.

**UI (CDG-365):** on `UserManagementPage`, when the selected user's `id` equals the session user's `id`, the organisation and role controls and the submit button are disabled, with the helper text "You cannot change your own organisation or role." The frontend still shows any `409` message from the server, and does not update the list optimistically.

> Note: under the current V13 trigger (one ADMIN per company), the only way to remove a company's last admin is the admin acting on themselves. That case is already covered by the self-assignment and self-removal rules. The last-admin rule is still implemented server-side so that it holds if the trigger is relaxed later (see Q1).

---

## 7. Data and configuration (owned by CDG-361 / CDG-363, developer A)

Flyway migration **V40** `V40__company_self_signup.sql` (V39 is taken by the maritime snapshot index from CDG-358, which merges first):

- `users_status_check` (from V22): allow `PENDING_VERIFICATION` in addition to `ACTIVE` and `REMOVED`. Add the value to the `AccountStatus` enum as well.
- New table, mirroring `password_reset_tokens` (V6):

```sql
CREATE TABLE email_verification_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX email_verification_tokens_token_hash_key ON email_verification_tokens (token_hash);
CREATE INDEX email_verification_tokens_user_id ON email_verification_tokens (user_id);
```

- Audit: new table `company_signup_audits (id BIGSERIAL PK, user_id BIGINT NOT NULL REFERENCES users(id), company_id BIGINT NOT NULL REFERENCES companies(id), event VARCHAR(32) NOT NULL CHECK (event IN ('REGISTERED','EMAIL_VERIFIED','RESUBMITTED')), recorded_at TIMESTAMPTZ NOT NULL)`. `REGISTERED` is written in the signup transaction; `EMAIL_VERIFIED` in the verification transaction; `RESUBMITTED` when a newer signup replaces a pending one (CDG-363).
- `users.status` is widened from `VARCHAR(16)` to `VARCHAR(32)`, because `PENDING_VERIFICATION` is 20 characters.
- `users.created_at` already exists (V1), so pending signups can be aged by a later cleanup job; V40 does not add it.

Configuration (suggested names and defaults, following `app.password-reset.*`):

| Key | Suggested default |
|---|---|
| `app.signup.link-base-url` | `http://localhost:5173/verify-email` |
| `app.signup.verification-ttl` | `PT24H` |
| `app.signup.rate-limit.ip` | 30 per window |
| `app.signup.rate-limit.email` | 3 per window |
| `app.signup.rate-limit.window` | `PT15M` |

**Client address behind the production proxy.** Production runs Vercel in front of Render, so `getRemoteAddr()` is the proxy's address for every request. The per-IP limit is therefore effectively a **global** ceiling, and the per-email limits do the real work. Forwarded headers (`X-Forwarded-For`) are not trusted, because they can be spoofed unless the proxy chain is pinned. Deriving the real client address from trusted proxy headers is a follow-up for both signup and password reset. The in-memory limiter prunes keys whose newest attempt is older than the window every 100th check, so its memory stays bounded.

**Test hook for E2E (CDG-366):** Playwright reads the real verification email from Mailpit (`docker-compose.yml`, SMTP 1025, HTTP API 8025), the same way `scripts/run-password-reset.mjs` does. No test-only token endpoint.

---

## 8. Frontend API functions (CDG-362, developer B)

To be added to `frontend/src/signup/api.ts`. Component tests mock these against this contract.

```ts
registerCompany(input: {companyCode: string; companyName: string; fullName: string; email: string; password: string}): Promise<{message: string}>
verifyEmail(token: string): Promise<{message: string}>
resendVerification(email: string): Promise<{message: string}>
```

Errors are thrown as the existing `ApiError` with `status`, `message` and field `errors`, so field-level messages map directly onto form fields.

---

## 9. Decisions (resolved)

- **Q1.** Keep the V13 "one ADMIN per company" trigger: **yes**. The last-admin rule is still enforced in the service layer.
- **Q2.** Public self-service company creation is intended by CDG-360 and coexists with the admin-only creation in CDG-313 (an admin still creates companies for invitations from the admin workspace).
- **Q3.** A taken organisation code returns `409` with `errors.companyCode` (CDG-362 requires a duplicate-company state).
- **Q4.** Cleanup of unverified companies/accounts is out of scope.
- **Q5.** `POST /api/register` (join path) keeps its current behaviour.
- **Q6.** V40: V39 went to the maritime snapshot index (CDG-358, #298/#310), which merges first.

Out of scope: platform-wide administrators, changes to the operator-only `/api/setup/administrator` bootstrap path, company types or approval workflows, and cleanup of unverified signups.
