import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const password = 'Example123';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function sql(query: string, variables: Record<string, string> = {}) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  if (useDocker) return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common], { cwd: root, input: query, encoding: 'utf8' }).trim();
  return execFileSync('psql', ['-h', '127.0.0.1', ...common], { cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' } }).trim();
}

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg147_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:cdg201-replay. It only writes to an owned disposable database.');
  }
  expect(sql('SELECT current_database();')).toBe(database);
  expect(sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();")).toBe(ownerToken);
}

type Account = { email: string; token: string; home: string };

async function register(request: APIRequestContext, role: 'FREIGHT_FORWARDER' | 'IMPORTER', company: string): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg201-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour' FROM companies WHERE code = :'company';`,
    { email, role, hash: createHash('sha256').update(invitation).digest('hex'), company });
  expect((await request.post('/api/register', { data: { fullName: 'CDG-201 Demo', email, password, invitationToken: invitation } })).status()).toBe(201);
  const login = await request.post('/api/login', { data: { email, password } });
  expect(login.status()).toBe(200);
  return { email, token: (await login.json()).token as string, home: role === 'IMPORTER' ? '/importer' : '/freight-forwarder' };
}

async function createShipment(request: APIRequestContext, token: string, reference: string, imo: string) {
  const response = await request.post('/api/shipments', { headers: { Authorization: `Bearer ${token}` }, data: {
    shipmentReference: reference, origin: 'Rotterdam', destination: 'Jakarta', transshipmentPort: 'Singapore',
    motherVessel: 'KOTA LARIS', motherVesselImo: imo, plannedMotherArrivalAt: '2026-10-28T23:00:00+08:00',
    feederVessel: 'Synthetic Feeder', plannedFeederDepartureAt: '2026-10-29T12:00:00+08:00',
  } });
  expect(response.status(), await response.text()).toBe(201);
  const created = await response.json() as { id: number };
  const detail = await request.get(`/api/shipments/${created.id}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(detail.status()).toBe(200);
  return { id: created.id, detail: await detail.json() as Record<string, unknown> };
}

async function signIn(page: Page, account: Account) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(account.home);
}

test.beforeAll(assertOwnedDatabase);

test('CDG-201 replay is authenticated, isolated, immutable, and visibly labelled', { tag: '@disposable-database' }, async ({ request, page }) => {
  test.setTimeout(120_000);
  const owner = await register(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const outsider = await register(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO');
  const suffix = randomBytes(4).toString('hex');
  const kota = await createShipment(request, owner.token, `CDG201-KOTA-${suffix}`, '9351048');
  const other = await createShipment(request, owner.token, `CDG201-OTHER-${suffix}`, '9387421');

  const unauthenticated = await request.get(`/api/shipments/${kota.id}/cdg201-arrival-evidence`);
  expect(unauthenticated.status()).toBe(401);

  const before = kota.detail;
  const replay = await request.get(`/api/shipments/${kota.id}/cdg201-arrival-evidence`, { headers: { Authorization: `Bearer ${owner.token}` } });
  expect(replay.status()).toBe(200);
  const body = await replay.json() as Record<string, any>;
  expect(body.readOnlyReplay).toBe(true);
  expect(body.evidence.vesselImo).toBe('9351048');
  expect(body.evidence.revisionMinutes).toBe(180);
  expect(body.evidence.shipmentAssociation).toBe('UNVERIFIED_IMO_ONLY');
  expect(body.evidence.sourceTimestampSemantics).toContain('undocumented');
  expect(body.evidence.locationMappingStatus).toBe('UNKNOWN_MPA_CODE_NOT_MAPPED_TO_DRIFT_PORT');
  expect(body.evidence.simulatedScenario).toMatchObject({ originalConnectionMarginMinutes: 780, revisedConnectionMarginMinutes: 600, reductionMinutes: 180 });

  const after = await request.get(`/api/shipments/${kota.id}`, { headers: { Authorization: `Bearer ${owner.token}` } });
  expect(after.status()).toBe(200);
  const afterBody = await after.json() as Record<string, unknown>;
  for (const key of ['plannedMotherArrivalAt', 'plannedFeederDepartureAt', 'connectionWindow', 'remainingConnectionWindow', 'risk', 'version']) {
    expect(afterBody[key]).toEqual(before[key]);
  }

  const noReplay = await request.get(`/api/shipments/${other.id}/cdg201-arrival-evidence`, { headers: { Authorization: `Bearer ${owner.token}` } });
  expect(noReplay.status()).toBe(200);
  expect((await noReplay.json()).evidence).toBeNull();

  const forbidden = await request.get(`/api/shipments/${kota.id}/cdg201-arrival-evidence`, { headers: { Authorization: `Bearer ${outsider.token}` } });
  // Existing authentication/access middleware may reject an inaccessible
  // account before shipment visibility as 401, or visibility may return 403.
  // Either response is safe only when it exposes no replay evidence.
  expect([401, 403]).toContain(forbidden.status());
  expect(await forbidden.text()).not.toContain('KOTA LARIS');
  const unknown = await request.get('/api/shipments/999999999/cdg201-arrival-evidence', { headers: { Authorization: `Bearer ${owner.token}` } });
  // The existing shipment visibility layer intentionally returns 401 for a
  // non-visible ID, including an unknown one.  Either this or a 404 is a
  // non-disclosing result; the endpoint must never return replay evidence.
  expect([401, 404]).toContain(unknown.status());
  expect(await unknown.text()).not.toContain('KOTA LARIS');

  await signIn(page, owner);
  await page.getByRole('link', { name: `CDG201-KOTA-${suffix}` }).click();
  const panel = page.getByRole('article', { name: 'CDG-201 external arrival evidence' });
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('KOTA LARIS · IMO 9351048');
  await expect(panel).toContainText('+180 minutes');
  await expect(panel).toContainText('UNVERIFIED IMO ONLY');
  await expect(panel).toContainText('UNKNOWN MPA CODE NOT MAPPED TO DRIFT PORT');
  await expect(panel).toContainText('780 → 600 minutes');
  await expect(panel).toContainText('Not the production shipment risk.');
});
