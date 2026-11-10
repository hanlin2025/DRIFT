"""Create a local development invitation; no email is sent."""

import argparse
import hashlib
from pathlib import Path
import secrets
import subprocess
from urllib.parse import urlencode


ROOT = Path(__file__).resolve().parents[1]


def psql(database: str, sql: str, variables: dict[str, str]) -> str:
    command = [
        "docker", "compose", "exec", "-T", "postgres", "psql",
        "-U", "drift", "-d", database, "-v", "ON_ERROR_STOP=1", "-At",
    ]
    for key, value in variables.items():
        command.extend(["-v", f"{key}={value}"])
    completed = subprocess.run(
        command,
        input=sql,
        text=True,
        check=True,
        cwd=ROOT,
        capture_output=True,
    )
    return completed.stdout.strip()


def require_active_company(database: str, company: str) -> str:
    code = company.strip().upper()
    found = psql(
        database,
        "SELECT code FROM companies WHERE UPPER(code) = :'company' AND active = TRUE;",
        {"company": code},
    )
    if found == "":
        raise SystemExit(f"No active organisation with code {code}")
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email", required=True)
    parser.add_argument(
        "--company",
        required=True,
        help="Active organisation code (demo seed or one created through the admin workspace)",
    )
    parser.add_argument("--role", required=True, choices=["IMPORTER", "FREIGHT_FORWARDER"])
    parser.add_argument("--database", default="drift", help="Local Docker database (default: drift)")
    args = parser.parse_args()
    email = args.email.strip().lower()
    if len(email) > 320 or email.count("@") != 1 or any(c.isspace() for c in email) or not all(email.split("@")):
        parser.error("Enter a valid email address")
    company = require_active_company(args.database, args.company)
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
         "-v", "ON_ERROR_STOP=1", "-v", f"email={email}", "-v", f"company={company}",
         "-v", f"role={args.role}", "-v", f"token_hash={token_hash}"],
        input=sql, text=True, check=True, cwd=ROOT,
    )
    print("Local invitation (expires in 7 days; keep this link private):")
    print("http://localhost:5173/signup#" + urlencode({"invite": token}))


if __name__ == "__main__":
    main()
