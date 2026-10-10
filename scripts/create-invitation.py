"""Create a local development invitation; no email is sent."""

import argparse
import hashlib
from pathlib import Path
import secrets
import subprocess
from urllib.parse import urlencode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email", required=True)
    parser.add_argument("--company", required=True, choices=["HARBOURLINE_DEMO", "STRAITS_FRESH_DEMO"])
    parser.add_argument("--role", required=True, choices=["IMPORTER", "FREIGHT_FORWARDER"])
    parser.add_argument("--database", default="drift", help="Local Docker database (default: drift)")
    args = parser.parse_args()
    email = args.email.strip().lower()
    if len(email) > 320 or email.count("@") != 1 or any(c.isspace() for c in email) or not all(email.split("@")):
        parser.error("Enter a valid email address")
    token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(token.encode()).hexdigest()
    sql = r"""
BEGIN;
SELECT id AS company_id FROM companies WHERE code = :'company' AND active = TRUE \gset
UPDATE invitations SET status = 'REVOKED' WHERE email = :'email' AND status = 'PENDING';
INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
VALUES (:'email', :company_id, :'role', :'token_hash', NOW() + INTERVAL '7 days');
COMMIT;
"""
    subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "drift", "-d", args.database,
         "-v", "ON_ERROR_STOP=1", "-v", f"email={email}", "-v", f"company={args.company}",
         "-v", f"role={args.role}", "-v", f"token_hash={token_hash}"],
        input=sql, text=True, check=True, cwd=Path(__file__).resolve().parents[1],
    )
    print("Local invitation (expires in 7 days; keep this link private):")
    print("http://localhost:5173/signup#" + urlencode({"invite": token}))


if __name__ == "__main__":
    main()
