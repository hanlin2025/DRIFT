# Gmail SMTP for production email

DRIFT uses Spring Boot's existing `JavaMailSender` for invitation, password-reset, and shipment-risk mail. Configure one sender identity and one frontend origin for all three flows. This changes delivery settings only; the local development defaults remain Mailpit and risk-alert dispatch stays disabled.

## Render environment

Set these on the backend web service in Render. Keep the password in Render's secret environment variable field and do not add it to source control.

| Variable | Value |
| --- | --- |
| `MAIL_HOST` | `smtp.gmail.com` |
| `MAIL_PORT` | `587` |
| `MAIL_USERNAME` | Gmail or Google Workspace sender account address |
| `MAIL_PASSWORD` | Google app password for that account |
| `MAIL_SMTP_AUTH` | `true` |
| `MAIL_SMTP_STARTTLS_ENABLE` | `true` |
| `MAIL_SMTP_STARTTLS_REQUIRED` | `true` |
| `MAIL_FROM_ADDRESS` | Same Gmail address as `MAIL_USERNAME`, or a verified send-as alias |
| `FRONTEND_BASE_URL` | The production Vercel frontend origin, for example `https://<your-production-domain>` |

Do not include a trailing slash in `FRONTEND_BASE_URL`. Invitation and password-reset links append `/signup` and `/reset-password`; risk-alert links use the same origin with the existing role path. Leave `INVITATION_LINK_BASE_URL`, `PASSWORD_RESET_LINK_BASE_URL`, and `RISK_ALERTS_FRONTEND_BASE_URL` unset unless an intentional per-message override is needed.

The deployment blueprint declares the SMTP settings but marks account-specific values as unsynchronized. Add the values in the Render dashboard. The repository's `render.yaml` is review-only and must not be applied without deployment approval.

## Gmail setup

Use a dedicated sender account where possible. Turn on 2-Step Verification, then create an app password in the Google Account security settings. Use that app password as `MAIL_PASSWORD`, not the account's normal password. Google only offers app passwords for eligible accounts; the option may be unavailable when 2-Step Verification is restricted to security keys, for work/school accounts whose administrator blocks it, or when Advanced Protection is enabled. If the account is ineligible, use a provider and authentication method approved for the organization instead of weakening the account's security.

Set `MAIL_FROM_ADDRESS` to the authenticated Gmail account or a send-as alias already verified in Gmail. Restart/redeploy the backend after setting the environment. Do not send a test message until an explicitly authorized recipient is selected.

## Render SMTP restriction

The checked-in Render blueprint specifies a `0.5c-512mb` web-service compute plan, not the Free plan. Render's current documentation says Free web services cannot send outbound traffic on ports 25, 465, or 587, and port 25 is blocked for all services. The blueprint is only a review artifact, so it does not prove which plan an active Render service currently uses. Confirm the live backend service's compute plan in the Render dashboard before enabling Gmail SMTP. Port 587 is available on paid web-service compute plans under Render's documented restriction.

If the actual service is Free, do not tunnel or proxy SMTP around the restriction. The smallest safe alternative is to move that service to a paid compute plan, subject to owner approval. If that is not approved, use an approved transactional mail API over HTTPS (port 443) and a separately scoped implementation; this change does not add that provider.

## Local Mailpit

With no email variables set, Spring Mail continues to use `localhost:1025` without SMTP authentication or STARTTLS. The shared sender defaults to `noreply@localhost`, and links use `http://localhost:5173`. Mailpit's web inbox remains at `http://localhost:8025`. Do not set Gmail credentials in local project files.

## Signup, login, and reset checks

The invitation-based signup path remains unchanged: the invitation link opens the existing signup flow, registration consumes the invitation, and login continues to use the existing HTTP-only session cookie. Password reset keeps its generic request response so it does not disclose whether an address has an account. A delivery failure discards the newly issued reset token and logs only the exception type; it never logs the SMTP exception message or credentials.

Automated verification uses the existing mocked `JavaMailSender` and Mailpit workflow. It does not send a real email. For a manual end-to-end check, use only a test recipient whose owner has explicitly authorized the send, then verify signup, cookie-authenticated navigation, and reset using the received links.
