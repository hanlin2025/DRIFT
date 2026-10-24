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

const REMAINING_HIGH = "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.";
const REMAINING_MEDIUM = "Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.";
const REMAINING_LOW = "At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.";
const PLANNED_USED = 'The planned connection window is being used.';
const OUTDATED = "The planned connection window is being used because the mother vessel's position is possibly outdated.";

type Account = { email: string; token: string; role: 'FREIGHT_FORWARDER' | 'IMPORTER' };

type Detail = {
  id: number;
  version: number;
  shipmentReference: string;
  transshipmentPort: string;
  motherVessel: string;
  feederVessel: string;
  connectionWindow: { duration: string; totalSeconds: number } | null;
  remainingConnectionWindow: {
    status: 'AVAILABLE' | 'MISSED';
    duration: string | null;
    totalSeconds: number | null;
    missedBy: string | null;
    missedBySeconds: number | null;
  } | null;
  risk: { level: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'; explanation: string } | null;
  arrivalComparison: {
    estimatedArrivalAt: string | null;
    lastReceivedAt: string | null;
    possiblyOutdated: boolean | null;
    unavailableReason: { code: string; message: string } | null;
  };
};

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg175_browser_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:remaining-connection-window. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the remaining connection window runner. Refusing to write fixtures.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  if (access !== 'compose' && access !== 'host') {
    throw new Error('Run this spec through npm run test:e2e:remaining-connection-window. The runner chooses the database access path.');
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
  return `WINDOW ${randomBytes(4).toString('hex').toUpperCase()}`;
}

async function register(request: APIRequestContext, companyCode: string, role: Account['role']): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg175-e2e-${randomBytes(8).toString('hex')}@example.com`;
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

async function createShipment(request: APIRequestContext, token: string, name: string, planned: string, departure: string, port = 'Singapore') {
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference: `HBL-CDG175-${randomBytes(4).toString('hex')}`,
      origin: 'Jakarta',
      destination: 'Rotterdam',
      transshipmentPort: port,
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
  return page.locator('.route-facts > dl > div').filter({ has: page.getByText(label, { exact: true }) }).locator('dd');
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('a usable estimate returns the planned window and the remaining window', { tag: '@disposable-database' }, async ({ request }) => {
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const name = vessel();
  const ingested = fresh();
  seed(name, ingested);
  const created = await createShipment(request, forwarder.token, name, fresh(-1), fresh(-2));
  expect(created.arrivalComparison.possiblyOutdated).toBe(false);
  expect(Date.parse(created.arrivalComparison.lastReceivedAt ?? '')).toBe(Date.parse(ingested));

  const positive = await placeFeeder(request, forwarder.token, created, -2 * HOUR, 6 * HOUR);
  expect(positive.connectionWindow).toMatchObject({ duration: '8 hours', totalSeconds: 8 * 60 * 60 });
  expect(positive.remainingConnectionWindow).toMatchObject({
    status: 'AVAILABLE', duration: '6 hours', totalSeconds: 6 * 60 * 60, missedBy: null, missedBySeconds: null,
  });
  expect(positive.risk).toEqual({ level: 'HIGH', explanation: REMAINING_HIGH });

  const underOneSecond = await placeFeeder(request, forwarder.token, positive, -HOUR, 500);
  expect(underOneSecond.remainingConnectionWindow).toMatchObject({
    status: 'AVAILABLE', duration: 'less than 1 second', totalSeconds: 0,
  });
  expect(underOneSecond.connectionWindow?.duration).toBe('1 hour');

  const missed = await placeFeeder(request, forwarder.token, underOneSecond, -3 * HOUR, -2 * HOUR);
  expect(missed.connectionWindow).toMatchObject({ duration: '1 hour', totalSeconds: 60 * 60 });
  expect(missed.remainingConnectionWindow).toMatchObject({
    status: 'MISSED', duration: null, totalSeconds: null, missedBy: '2 hours', missedBySeconds: 2 * 60 * 60,
  });
  expect(missed.risk).toEqual({
    level: 'CRITICAL', explanation: 'The connection is expected to be missed by 2 hours.',
  });
});

test('risk follows the window that was used, including the 12 hour and 24 hour boundaries', { tag: '@disposable-database' }, async ({ request }) => {
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const name = vessel();
  seed(name, fresh());
  const created = await createShipment(request, forwarder.token, name, fresh(-1), fresh(-2));

  const justUnder = await placeFeeder(request, forwarder.token, created, -HOUR, 12 * HOUR - 1000);
  expect(justUnder.remainingConnectionWindow).toMatchObject({
    status: 'AVAILABLE', duration: '11 hours 59 minutes 59 seconds', totalSeconds: 12 * 60 * 60 - 1,
  });
  expect(justUnder.risk).toEqual({ level: 'HIGH', explanation: REMAINING_HIGH });

  const twelve = await placeFeeder(request, forwarder.token, justUnder, -HOUR, 12 * HOUR);
  expect(twelve.remainingConnectionWindow).toMatchObject({ status: 'AVAILABLE', duration: '12 hours', totalSeconds: 12 * 60 * 60 });
  expect(twelve.risk).toEqual({ level: 'MEDIUM', explanation: REMAINING_MEDIUM });

  const twentyFour = await placeFeeder(request, forwarder.token, twelve, -HOUR, 24 * HOUR);
  expect(twentyFour.remainingConnectionWindow).toMatchObject({ status: 'AVAILABLE', duration: '1 day', totalSeconds: 24 * 60 * 60 });
  expect(twentyFour.risk).toEqual({ level: 'LOW', explanation: REMAINING_LOW });
});

test('a just-overdue position and each stored unavailable estimate fall back to the planned window', { tag: '@disposable-database' }, async ({ request }) => {
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');

  const outdatedName = vessel();
  seed(outdatedName, fresh(6 + 1 / 60));
  const outdated = await createShipment(request, forwarder.token, outdatedName, fresh(-1), fresh(-7));
  expect(outdated.arrivalComparison.possiblyOutdated).toBe(true);
  expect(outdated.remainingConnectionWindow).toBeNull();
  expect(outdated.connectionWindow).toMatchObject({ duration: '6 hours', totalSeconds: 6 * 60 * 60 });
  expect(outdated.risk?.level).toBe('HIGH');
  expect(outdated.risk?.explanation).toBe(`Less than 12 hours to transfer cargo between vessels. ${PLANNED_USED}`);

  const missingName = vessel();
  const missing = await createShipment(request, forwarder.token, missingName, fresh(-1), fresh(-7));
  expect(missing.arrivalComparison.unavailableReason).toEqual({
    code: 'NO_POSITION', message: 'No AIS position has been received for the mother vessel.',
  });
  expect(missing.remainingConnectionWindow).toBeNull();
  expect(missing.risk?.explanation).toBe(`Less than 12 hours to transfer cargo between vessels. ${PLANNED_USED}`);

  const unknownName = vessel();
  seed(unknownName, fresh());
  const unknown = await createShipment(request, forwarder.token, unknownName, fresh(-1), fresh(-7), 'Atlantis');
  expect(unknown.arrivalComparison.unavailableReason).toEqual({
    code: 'UNKNOWN_PORT',
    message: 'The transshipment port is not on the route map, so its coordinates cannot be used.',
  });
  expect(unknown.remainingConnectionWindow).toBeNull();
  expect(unknown.risk?.explanation).toContain(PLANNED_USED);

  const slowName = vessel();
  seed(slowName, fresh(), '0.4');
  const slow = await createShipment(request, forwarder.token, slowName, fresh(-1), fresh(-7));
  expect(slow.arrivalComparison.unavailableReason).toEqual({
    code: 'UNUSABLE_SPEED', message: "The mother vessel's speed is missing or below 0.5 knots.",
  });
  expect(slow.remainingConnectionWindow).toBeNull();
  expect(slow.risk?.explanation).toContain(PLANNED_USED);

  const blankName = vessel();
  seed(blankName, fresh());
  const blank = await createShipment(request, forwarder.token, blankName, fresh(-1), fresh(-7));
  sql("UPDATE shipments SET transshipment_port = '' WHERE id = :'id'", { id: String(blank.id) });
  const cleared = await read(request, forwarder.token, blank.id);
  expect(cleared.arrivalComparison.unavailableReason).toEqual({
    code: 'MISSING_PORT', message: 'The transshipment port is missing.',
  });
  expect(cleared.remainingConnectionWindow).toBeNull();
  expect(cleared.risk?.explanation).toContain(PLANNED_USED);
});

test('another company receives no window and a linked importer can see the shipment', { tag: '@disposable-database' }, async ({ page, request }) => {
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const outsider = await register(request, 'STRAITS_FRESH_DEMO', 'FREIGHT_FORWARDER');
  const importer = await register(request, 'STRAITS_FRESH_DEMO', 'IMPORTER');
  const name = vessel();
  seed(name, fresh());
  const created = await createShipment(request, forwarder.token, name, fresh(-1), fresh(-2));
  const detail = await placeFeeder(request, forwarder.token, created, -2 * HOUR, 6 * HOUR);

  const denied = await request.get(`/api/shipments/${detail.id}`, { headers: { Authorization: `Bearer ${outsider.token}` } });
  expect(denied.status()).toBe(403);
  expect(await denied.json()).toEqual({ message: 'You do not have access to this shipment' });

  await signIn(page, outsider);
  await page.goto(`/freight-forwarder/shipments/${detail.id}`);
  await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
  await expect(page.getByText('Remaining connection window')).toHaveCount(0);
  await expect(page.getByText('Connection window', { exact: true })).toHaveCount(0);

  const importerCompanyId = sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'");
  const linked = await request.patch(`/api/shipments/${detail.id}/link-importer`, {
    headers: { Authorization: `Bearer ${forwarder.token}` },
    data: { importerCompanyId: Number(importerCompanyId) },
  });
  expect(linked.status(), await linked.text()).toBe(200);
  const linkedBody = await linked.json() as Detail;
  expect(linkedBody.connectionWindow?.duration).toBe('8 hours');
  expect(linkedBody.remainingConnectionWindow?.duration).toBe('6 hours');
  expect(linkedBody.risk?.explanation).toBe(REMAINING_HIGH);

  const seen = await read(request, importer.token, detail.id);
  expect(seen.connectionWindow?.duration).toBe('8 hours');
  expect(seen.remainingConnectionWindow).toMatchObject({ status: 'AVAILABLE', duration: '6 hours' });
  expect(seen.risk?.explanation).toBe(REMAINING_HIGH);

  await signIn(page, importer);
  await page.goto(`/importer/shipments/${detail.id}`);
  await expect(page.getByRole('heading', { name: detail.shipmentReference })).toBeVisible();
  await expect(fact(page, 'Connection window')).toHaveText('8 hours');
  await expect(fact(page, 'Remaining connection window')).toHaveText('6 hours');
  await expect(fact(page, 'Risk')).toContainText('High');
  await expect(fact(page, 'Risk')).toContainText(REMAINING_HIGH);
});

test('the page shows the planned window, the remaining window, a missed connection, a fallback, and the matching risk badge', { tag: '@disposable-database' }, async ({ page, request }) => {
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  await signIn(page, forwarder);

  const usableName = vessel();
  seed(usableName, fresh());
  const created = await createShipment(request, forwarder.token, usableName, fresh(-1), fresh(-2));
  const usable = await placeFeeder(request, forwarder.token, created, -2 * HOUR, 6 * HOUR);
  await page.goto(`/freight-forwarder/shipments/${usable.id}`);
  await expect(fact(page, 'Connection window')).toHaveText('8 hours');
  await expect(fact(page, 'Remaining connection window')).toHaveText('6 hours');
  await expect(fact(page, 'Risk').locator('.risk-badge')).toHaveText('High');
  await expect(fact(page, 'Risk')).toContainText(REMAINING_HIGH);

  const missed = await placeFeeder(request, forwarder.token, usable, -3 * HOUR, -2 * HOUR);
  await page.goto(`/freight-forwarder/shipments/${missed.id}`);
  await expect(fact(page, 'Connection window')).toHaveText('1 hour');
  await expect(fact(page, 'Remaining connection window')).toHaveText('The connection is expected to be missed by 2 hours.');
  await expect(fact(page, 'Risk').locator('.risk-badge')).toHaveText('Critical');

  const staleName = vessel();
  seed(staleName, fresh(7));
  const stale = await createShipment(request, forwarder.token, staleName, fresh(-1), fresh(-7));
  await page.goto(`/freight-forwarder/shipments/${stale.id}`);
  await expect(fact(page, 'Connection window')).toHaveText('6 hours');
  await expect(fact(page, 'Remaining connection window')).toHaveText(OUTDATED);
  await expect(fact(page, 'Risk')).toContainText(PLANNED_USED);
  await expect(fact(page, 'Risk').locator('.risk-badge')).toHaveText('High');

  const bareName = vessel();
  const bare = await createShipment(request, forwarder.token, bareName, fresh(-1), fresh(-7));
  await page.goto(`/freight-forwarder/shipments/${bare.id}`);
  await expect(fact(page, 'Remaining connection window')).toHaveText(
    `${PLANNED_USED} No AIS position has been received for the mother vessel.`);
});
