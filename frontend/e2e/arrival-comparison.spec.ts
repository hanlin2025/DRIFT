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
const LIMITATION = 'Approximate arrival — based on straight-line distance and current speed.';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg152_browser_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:arrival-comparison. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the arrival comparison runner. Refusing to write fixtures.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  if (access !== 'compose' && access !== 'host') {
    throw new Error('Run this spec through npm run test:e2e:arrival-comparison. The runner chooses the database access path.');
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

type Account = { email: string; token: string };

async function registerForwarder(request: APIRequestContext): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg152-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = 'HARBOURLINE_DEMO';`, {
    email, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName: 'Alex Tan', email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  return { email, token: (await signedIn.json()).token as string };
}

function seed(vessel: string, identifier: string, ingestedAt: string) {
  sql(`INSERT INTO vessel_observations (
      mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
    VALUES (:'mmsi', :'vessel', 1.26, 103.82, 0.5, :'ingested', 'SEEDED');`,
    { mmsi: identifier, vessel, ingested: ingestedAt });
}

async function createShipment(request: APIRequestContext, token: string, vessel: string, planned: string) {
  const departure = new Date(Date.parse(planned) + 6 * 60 * 60 * 1000).toISOString();
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference: `HBL-CDG152-${randomBytes(4).toString('hex')}`,
      origin: 'Jakarta',
      destination: 'Rotterdam',
      transshipmentPort: 'Singapore',
      motherVessel: vessel,
      plannedMotherArrivalAt: planned,
      feederVessel: 'Feeder',
      plannedFeederDepartureAt: departure,
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const created = await response.json() as { id: number };
  const detail = await request.get(`/api/shipments/${created.id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(detail.status(), await detail.text()).toBe(200);
  return await detail.json() as Detail;
}

type Comparison = {
  estimatedArrivalAt: string;
  limitation: string;
  speedOverGroundKnots: number;
  difference: string;
  lastReceivedAt: string;
  possiblyOutdated: boolean;
};

type Detail = {
  id: number;
  shipmentReference: string;
  plannedMotherArrivalAt: string;
  arrivalComparison: Comparison;
};

function sameInstant(actual: string | null, expected: string) {
  expect(actual, 'a time element needs a dateTime value').toBeTruthy();
  expect(Date.parse(actual as string)).toBe(Date.parse(expected));
}

async function signIn(page: Page, account: Account) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');
}

async function expectComparison(page: Page, detail: Detail) {
  await page.goto(`/freight-forwarder/shipments/${detail.id}`);
  await expect(page.getByRole('heading', { name: detail.shipmentReference })).toBeVisible();
  const planned = page.locator('.route-facts > dl > div').filter({ has: page.getByText('Planned arrival', { exact: true }) });
  sameInstant(await planned.locator(':scope > dd > time').getAttribute('datetime'), detail.plannedMotherArrivalAt);
  const comparison = planned.locator('dl.arrival-comparison');
  const estimated = comparison.locator('div').filter({ has: page.getByText('Estimated arrival', { exact: true }) });
  const received = comparison.locator('div').filter({ has: page.getByText('Last received', { exact: true }) });
  sameInstant(await estimated.locator('time').getAttribute('datetime'), detail.arrivalComparison.estimatedArrivalAt);
  sameInstant(await received.locator('time').getAttribute('datetime'), detail.arrivalComparison.lastReceivedAt);
  await expect(comparison.getByText(detail.arrivalComparison.difference, { exact: true })).toBeVisible();
  await expect(comparison.getByText(LIMITATION, { exact: true })).toBeVisible();
  await expect(comparison.getByText(`${detail.arrivalComparison.speedOverGroundKnots} knots`, { exact: true })).toBeVisible();
  await expect(page.getByText('6 hours', { exact: true })).toBeVisible();
  await expect(page.getByText('High', { exact: true })).toBeVisible();
  if (detail.arrivalComparison.possiblyOutdated) {
    await expect(comparison.getByText('Possibly outdated', { exact: true })).toBeVisible();
  } else {
    await expect(comparison.getByText('Possibly outdated')).toHaveCount(0);
  }
  return estimated.locator('time').getAttribute('datetime');
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('the detail page shows the persisted arrival comparison', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const fresh = new Date(Date.now() - 60_000).toISOString();
  const outdated = new Date(Date.now() - 7 * 60 * 60 * 1000).toISOString();
  const owner = await registerForwarder(request);
  const cases = [
    { vessel: 'Arrival Ahead', ingested: fresh, planned: new Date(Date.parse(fresh) + 90 * 60 * 1000).toISOString(), difference: 'Ahead of plan · estimated 1h 30m earlier than planned' },
    { vessel: 'Arrival Gap', ingested: fresh, planned: new Date(Date.parse(fresh) - 90 * 60 * 1000).toISOString(), difference: 'On schedule · estimated 1h 30m later than planned' },
    { vessel: 'Arrival Behind', ingested: fresh, planned: new Date(Date.parse(fresh) - 6 * 60 * 60 * 1000).toISOString(), difference: 'Behind plan · estimated 6h later than planned' },
    { vessel: 'Arrival Old', ingested: outdated, planned: outdated, difference: 'On schedule · estimated at the planned time' },
  ];
  const details: Detail[] = [];
  for (const item of cases) {
    const identifier = mmsi();
    seed(item.vessel, identifier, item.ingested);
    const detail = await createShipment(request, owner.token, item.vessel, item.planned);
    expect(detail.arrivalComparison.difference).toBe(item.difference);
    expect(detail.arrivalComparison.limitation).toBe(LIMITATION);
    expect(detail.arrivalComparison.speedOverGroundKnots).toBe(0.5);
    expect(Date.parse(detail.arrivalComparison.estimatedArrivalAt)).toBe(Date.parse(item.ingested));
    expect(Date.parse(detail.arrivalComparison.lastReceivedAt)).toBe(Date.parse(item.ingested));
    expect(detail.arrivalComparison.possiblyOutdated).toBe(item.vessel === 'Arrival Old');
    details.push(detail);
  }

  await signIn(page, owner);
  const firstEstimate = await expectComparison(page, details[0]);
  for (const detail of details.slice(1)) {
    await expectComparison(page, detail);
  }
  await page.goto(`/freight-forwarder/shipments/${details[0].id}`);
  const planned = page.locator('.route-facts > dl > div').filter({ has: page.getByText('Planned arrival', { exact: true }) });
  const revisited = await planned.locator('dl.arrival-comparison time').first().getAttribute('datetime');
  sameInstant(revisited, firstEstimate as string);
  sameInstant(revisited, details[0].arrivalComparison.estimatedArrivalAt);
});
