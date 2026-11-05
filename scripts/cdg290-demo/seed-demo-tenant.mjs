import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const envPath = process.argv[2];
if (!envPath || !/^\/tmp\/drift-cdg290-offline-[^/]+\/demo\.env$/.test(envPath)) {
  throw new Error('Expected the generated demo.env file under /tmp/drift-cdg290-offline-*.');
}
const values = Object.fromEntries(readFileSync(envPath, 'utf8').split(/\r?\n/)
  .filter(line => /^[A-Z0-9_]+=/.test(line))
  .map(line => { const split = line.indexOf('='); return [line.slice(0, split), line.slice(split + 1)]; }));
const required = ['RUN_ID', 'CONTAINER_NAME', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'DB_PORT',
  'BACKEND_PORT', 'DEMO_COMPANY_ID', 'DEMO_USER_ID', 'DEMO_SHIPMENT_A_ID', 'DEMO_SHIPMENT_B_ID', 'DEMO_EMAIL'];
for (const key of required) if (!values[key]) throw new Error(`Missing ${key} in generated demo configuration.`);
if (!process.env.DEMO_PASSWORD || !/[a-z]/.test(process.env.DEMO_PASSWORD)
  || !/[A-Z]/.test(process.env.DEMO_PASSWORD) || !/[0-9]/.test(process.env.DEMO_PASSWORD)
  || Buffer.byteLength(process.env.DEMO_PASSWORD, 'utf8') < 8) {
  throw new Error('The local demo password must contain at least 8 bytes, uppercase, lowercase and a number.');
}

const label = execFileSync('docker', ['inspect', '--format', '{{ index .Config.Labels "drift.demo" }}', values.CONTAINER_NAME], { encoding: 'utf8' }).trim();
const runLabel = execFileSync('docker', ['inspect', '--format', '{{ index .Config.Labels "drift.demo.run" }}', values.CONTAINER_NAME], { encoding: 'utf8' }).trim();
if (label !== 'cdg290-offline' || runLabel !== values.RUN_ID) throw new Error('Container identity check failed; refusing to seed.');

const token = randomBytes(32).toString('base64url');
const tokenHash = createHash('sha256').update(token).digest('hex');
const sql = `
INSERT INTO companies (id, code, name, active)
VALUES (29000, 'CDG290_OFFLINE_DEMO', 'CDG-290 Offline Scenario Demo', TRUE);
SELECT setval(pg_get_serial_sequence('companies', 'id'), 29000, TRUE);
SELECT setval(pg_get_serial_sequence('users', 'id'), 29000, TRUE);
SELECT setval(pg_get_serial_sequence('shipments', 'id'), 29000, TRUE);
INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
VALUES ('${values.DEMO_EMAIL}', 29000, 'FREIGHT_FORWARDER', '${tokenHash}', NOW() + INTERVAL '4 hours');
`;
execFileSync('docker', ['exec', '-i', values.CONTAINER_NAME, 'psql', '--set', 'ON_ERROR_STOP=1',
  '--username', values.DB_USER, '--dbname', values.DB_NAME], { input: sql, stdio: ['pipe', 'ignore', 'inherit'] });

const api = `http://127.0.0.1:${values.BACKEND_PORT}`;
async function request(path, body, tokenValue) {
  const response = await fetch(`${api}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(tokenValue ? { Authorization: `Bearer ${tokenValue}` } : {}) },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`${path} returned HTTP ${response.status}; check backend readiness and demo migration status.`);
  return response.json();
}

try {
  const registered = await request('/api/register', {
    fullName: 'CDG-290 Offline Demo Presenter', email: values.DEMO_EMAIL,
    password: process.env.DEMO_PASSWORD, invitationToken: token,
  });
  if (String(registered.id) !== values.DEMO_USER_ID) throw new Error('Unexpected demo user sequence; refuse to enable the scenario for another account.');
  const login = await request('/api/login', { email: values.DEMO_EMAIL, password: process.env.DEMO_PASSWORD });
  if (String(login.id) !== values.DEMO_USER_ID || String(login.company?.id) !== values.DEMO_COMPANY_ID) {
    throw new Error('Demo account identity does not match the isolated scenario configuration.');
  }
  const headers = login.token;
  const base = {
    origin: 'Shanghai, CN', destination: 'Jakarta, ID', transshipmentPort: 'Singapore',
    motherVessel: 'MV Pacific Horizon', plannedMotherArrivalAt: '2026-10-01T06:00:00Z',
    feederVessel: 'MV Strait Runner', plannedFeederDepartureAt: '2026-10-02T12:00:00Z',
  };
  const a = await request('/api/shipments', { ...base, shipmentReference: 'CDG290-OFFLINE-A' }, headers);
  const b = await request('/api/shipments', { ...base, shipmentReference: 'CDG290-OFFLINE-B' }, headers);
  if (String(a.id) !== values.DEMO_SHIPMENT_A_ID || String(b.id) !== values.DEMO_SHIPMENT_B_ID) {
    throw new Error('Unexpected shipment sequence; refuse to continue because scenario bindings would be wrong.');
  }
  console.log(`Demo tenant and two ordinary anchor shipments created in disposable database ${values.DB_NAME}.`);
  console.log(`Presenter login: ${values.DEMO_EMAIL} (password entered privately; never written to disk).`);
  console.log(`Shipment A ID: ${a.id}; Shipment B ID: ${b.id}.`);
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Demo setup failed.');
  process.exitCode = 1;
}
