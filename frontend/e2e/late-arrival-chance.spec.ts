import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const access = process.env.DRIFT_E2E_PSQL ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const HOUR = 60 * 60 * 1000;

const CHANCE_ROW = 'Chance of arriving after feeder departure';
const NO_POSITION = 'Chance of arriving after feeder departure unavailable: no position has been received for the mother vessel.';

type Account = { email: string; token: string; role: 'FREIGHT_FORWARDER' | 'IMPORTER' };

type Detail = {
  id: number;
  version: number;
  shipmentReference: string;
  transshipmentPort: string;
  motherVessel: string;
  feederVessel: string;
  arrivalComparison: {
    estimatedArrivalAt: string | null;
    lastReceivedAt: string | null;
    possiblyOutdated: boolean | null;
    unavailableReason: { code: string; message: string } | null;
  };
  lateArrivalChance: {
    available: boolean;
    probability: number | null;
    display: string | null;
    arrivalUsed: string | null;
    plannedFeederDepartureUsed: string | null;
    plannedWindowInvalid: boolean | null;
    unavailableReason: { code: string; message: string } | null;
  };
};

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg210_browser_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:late-arrival-chance. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the late-arrival chance runner. Refusing to write fixtures.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  if (access !== 'compose' && access !== 'host') {
    throw new Error('Run this spec through npm run test:e2e:late-arrival-chance. The runner chooses the database access path.');
  }
  const common = ['-h', '127.0.0.1', '-p', '5432', '-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  if (access === 'compose') {
    return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common],
      { cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' } }).trim();
  }
  return execFileSync('psql', common, {
    cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  }).trim();
}

function mmsi() {
  return `6${randomBytes(4).readUInt32BE(0).toString().padStart(8, '0').slice(0, 8)}`;
}

function vessel() {
  return `CHANCE ${randomBytes(4).toString('hex').toUpperCase()}`;
}

async function register(request: APIRequestContext, companyCode: string, role: Account['role']): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg210-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company: companyCode, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName: 'Alex Tan', email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  return { email, token: (await signedIn.json()).token as string, role };
}

function seed(name: string, ingestedAt: string, speed = '12') {
  sql(`INSERT INTO vessel_observations (
      mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
    VALUES (:'mmsi', :'vessel', 1.26, 103.82, :'speed', :'ingested', 'SEEDED');`,
    { mmsi: mmsi(), vessel: name, speed, ingested: ingestedAt });
}

async function createShipment(request: APIRequestContext, token: string, name: string, planned: string, departure: string) {
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference: `HBL-CDG210-${randomBytes(4).toString('hex')}`,
      origin: 'Jakarta',
      destination: 'Rotterdam',
      transshipmentPort: 'Singapore',
      motherVessel: name,
      plannedMotherArrivalAt: planned,
      feederVessel: 'Feeder',
      plannedFeederDepartureAt: departure,
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const created = await response.json() as { id: number };
  return read(request, token, created.id);
}

async function read(request: APIRequestContext, token: string, id: number) {
  const detail = await request.get(`/api/shipments/${id}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(detail.status(), await detail.text()).toBe(200);
  return await detail.json() as Detail;
}

async function placeFeeder(request: APIRequestContext, token: string, detail: Detail, plannedOffsetMs: number, feederOffsetMs: number) {
  const eta = Date.parse(detail.arrivalComparison.estimatedArrivalAt ?? '');
  expect(Number.isFinite(eta)).toBe(true);
  const updated = await request.put(`/api/shipments/${detail.id}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference: detail.shipmentReference,
      origin: 'Jakarta',
      destination: 'Rotterdam',
      transshipmentPort: detail.transshipmentPort,
      motherVessel: detail.motherVessel,
      plannedMotherArrivalAt: new Date(eta + plannedOffsetMs).toISOString(),
      feederVessel: detail.feederVessel,
      plannedFeederDepartureAt: new Date(eta + feederOffsetMs).toISOString(),
      version: detail.version,
    },
  });
  expect(updated.status(), await updated.text()).toBe(200);
  return read(request, token, detail.id);
}

function fresh(hoursAgo = 0) {
  return new Date(Date.now() - hoursAgo * HOUR).toISOString();
}

async function signIn(page: Page, account: Account) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(account.role === 'IMPORTER' ? '/importer' : '/freight-forwarder');
}

function fact(page: Page, label: string) {
  return page.locator('.route-facts > dl > div').filter({ has: page.getByText(label, { exact: true }) }).locator(':scope > dd');
}

// Opens the page and returns the detail response the page itself rendered, so the test compares
// the page with the value the API returned on that read instead of a value written into the test.
async function openDetail(page: Page, id: number) {
  const response = page.waitForResponse(candidate => new URL(candidate.url()).pathname === `/api/shipments/${id}`
    && candidate.request().method() === 'GET' && candidate.status() === 200);
  await page.goto(`/freight-forwarder/shipments/${id}`);
  const detail = await (await response).json() as Detail;
  await expect(page.getByRole('article', { name: `Shipment ${detail.shipmentReference}` })).toBeVisible();
  return detail;
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('the page shows the returned chance as a rough estimate, and the reason when there is no position', { tag: '@disposable-database' }, async ({ page, request }) => {
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  await signIn(page, forwarder);

  const usableName = vessel();
  seed(usableName, fresh());
  const created = await createShipment(request, forwarder.token, usableName, fresh(-1), fresh(-2));
  const usable = await placeFeeder(request, forwarder.token, created, -2 * HOUR, 2 * HOUR);
  expect(usable.arrivalComparison.possiblyOutdated).toBe(false);
  expect(usable.lateArrivalChance.available).toBe(true);

  const shown = await openDetail(page, usable.id);
  const chance = shown.lateArrivalChance;
  expect(chance.available).toBe(true);
  expect(chance.display).toMatch(/^(<5%|>95%|5%|[1-9][05]%)$/);
  expect(chance.arrivalUsed).toBe(shown.arrivalComparison.estimatedArrivalAt);
  const value = fact(page, CHANCE_ROW);
  await expect(value).toHaveCount(1);
  expect(await value.evaluate(element => element.firstChild?.textContent)).toBe(`${chance.display} · rough estimate`);
  await expect(value).not.toHaveClass(/is-missing/);
  await expect(value.getByText('Estimated arrival used', { exact: true })).toBeVisible();
  await expect(value.locator('time').first()).toHaveAttribute('datetime', chance.arrivalUsed ?? '');
  await expect(value.getByText('Planned feeder departure used', { exact: true })).toBeVisible();
  await expect(value.locator('time').nth(1)).toHaveAttribute('datetime', chance.plannedFeederDepartureUsed ?? '');
  await expect(value.locator('.risk-badge')).toHaveCount(0);
  await expect(fact(page, 'Risk').locator('.risk-badge')).toBeVisible();

  const bareName = vessel();
  const bare = await createShipment(request, forwarder.token, bareName, fresh(-1), fresh(-7));
  const missing = await openDetail(page, bare.id);
  expect(missing.arrivalComparison.unavailableReason?.code).toBe('NO_POSITION');
  expect(missing.lateArrivalChance).toMatchObject({
    available: false, probability: null, display: null, unavailableReason: { code: 'NO_POSITION', message: NO_POSITION },
  });
  const reason = fact(page, CHANCE_ROW);
  await expect(reason).toHaveText(missing.lateArrivalChance.unavailableReason?.message ?? '');
  await expect(reason).toHaveClass(/is-missing/);
  await expect(reason).not.toContainText('%');
});
