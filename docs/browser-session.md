# Browser sessions (CDG-311)

Signing in sets an HttpOnly cookie named `DRIFT_SESSION`. The browser keeps that cookie for the site, so a new tab is still signed in and still has the same role and organisation. The cookie is `SameSite=Lax`, host-only, and `Secure` when the request is HTTPS or `SESSION_COOKIE_SECURE=true`. Local HTTP through the Vite proxy leaves `Secure` off.

The cookie lasts eight hours (`JWT_EXPIRATION_MS`, default `28800000`). It is not stored in `localStorage` or `sessionStorage`. The page keeps only the account profile, with an empty token, so API calls from the browser send the cookie. A state-changing cookie request must include `X-DRIFT-Request: 1`. A request that already sends `Authorization: Bearer` does not use that header and does not read the cookie.

Sign out calls `POST /api/logout`, clears the cookie, and increments the account's session generation. A copied token or another tab cannot use the old session after sign-out, expiry, a role or organisation change, a password change, or account removal.
