import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const NO_ACCESS = 'You do not have access to this shipment';
const NOT_FOUND = 'Shipment not found or has been removed.';
const NO_POSITION = 'No live AIS position';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg101_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:detail. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the shipment detail runner. Refusing to write fixtures.');
  }
}

const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function sql(query: string, variables: Record<string, string> = {}) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  if (useDocker) {
    return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common],
      { cwd: root, input: query, encoding: 'utf8' }).trim();
  }
  return execFileSync('psql', ['-h', '127.0.0.1', ...common], {
    cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  }).trim();
}

type Account = { email: string; token: string };

async function registerAccount(request: APIRequestContext, company: string): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg101-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName: 'Alex Tan', email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  return { email, token: (await signedIn.json()).token };
}

const itinerary = {
  origin: 'Rotterdam',
  destination: 'Jakarta',
  transshipmentPort: 'Singapore',
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-15T08:00:00+08:00',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-15T17:00:00+08:00',
};

async function createShipment(request: APIRequestContext, token: string) {
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: { ...itinerary, shipmentReference: `CDG101-${randomBytes(4).toString('hex')}` },
  });
  expect(response.status(), await response.text()).toBe(201);
  return await response.json() as { id: number; shipmentReference: string; plannedMotherArrivalAt: string; plannedFeederDepartureAt: string };
}

function cleanup(emails: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
}

async function signIn(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');
}

function fact(page: Page, label: string) {
  return page.locator('.route-facts dl > div').filter({ has: page.getByText(label, { exact: true }) }).locator('dd');
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('an authorised freight forwarder opens a shipment from the list and sees every field and its risk', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The detail page is covered once on desktop.');
  const owner = await registerAccount(request, 'HARBOURLINE_DEMO');
  try {
    const shipment = await createShipment(request, owner.token);
    await signIn(page, owner.email);
    await page.getByRole('link', { name: shipment.shipmentReference }).click();
    await expect(page).toHaveURL(`/freight-forwarder/shipments/${shipment.id}`);

    for (const open of [async () => {}, () => page.reload()]) {
      await open();
      await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
      await expect(fact(page, 'Origin')).toHaveText(itinerary.origin);
      await expect(fact(page, 'Destination')).toHaveText(itinerary.destination);
      await expect(fact(page, 'Transshipment port')).toHaveText(itinerary.transshipmentPort);
      await expect(fact(page, 'Mother vessel')).toHaveText(itinerary.motherVessel);
      await expect(fact(page, 'Feeder vessel')).toHaveText(itinerary.feederVessel);
      await expect(fact(page, 'Planned arrival').locator('time')).toHaveAttribute('datetime', shipment.plannedMotherArrivalAt);
      await expect(fact(page, 'Planned departure').locator('time')).toHaveAttribute('datetime', shipment.plannedFeederDepartureAt);
      await expect(fact(page, 'Risk')).toHaveText('High Less than 12 hours to transfer cargo between vessels.');
    }
  } finally {
    cleanup([owner.email]);
  }
});

test('another company does not see the shipment and gets the access denied page', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The detail page is covered once on desktop.');
  const owner = await registerAccount(request, 'HARBOURLINE_DEMO');
  const outsider = await registerAccount(request, 'STRAITS_FRESH_DEMO');
  try {
    const shipment = await createShipment(request, owner.token);
    const list = page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/shipments');
    await signIn(page, outsider.email);
    expect((await (await list).json()).map((item: { id: number }) => item.id)).not.toContain(shipment.id);

    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    await expect(page.getByText(NO_ACCESS)).toBeVisible();
    await expect(page.getByText(shipment.shipmentReference)).toHaveCount(0);
  } finally {
    cleanup([owner.email, outsider.email]);
  }
});

test('an unknown or invalid shipment id shows the not found page', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The detail page is covered once on desktop.');
  const owner = await registerAccount(request, 'HARBOURLINE_DEMO');
  try {
    await signIn(page, owner.email);
    for (const id of ['999999999', 'not-a-number']) {
      const detail = await request.get(`/api/shipments/${id}`, { headers: { Authorization: `Bearer ${owner.token}` } });
      expect(detail.status()).toBe(404);
      await page.goto(`/freight-forwarder/shipments/${id}`);
      await expect(page.getByRole('heading', { name: 'Shipment not found' })).toBeVisible();
      await expect(page.getByText(NOT_FOUND)).toBeVisible();
    }
  } finally {
    cleanup([owner.email]);
  }
});

test('a missing feeder vessel and live positions show placeholders instead of blanks', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The detail page is covered once on desktop.');
  const owner = await registerAccount(request, 'HARBOURLINE_DEMO');
  try {
    const shipment = await createShipment(request, owner.token);
    sql("UPDATE shipments SET feeder_vessel = '' WHERE id = :'id';", { id: String(shipment.id) });

    await signIn(page, owner.email);
    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(fact(page, 'Feeder vessel')).toHaveText('Pending assignment');
    await expect(fact(page, 'Feeder vessel')).toHaveClass('is-missing');
    await expect(fact(page, 'Mother vessel position')).toHaveText(NO_POSITION);
    await expect(fact(page, 'Feeder vessel position')).toHaveText(NO_POSITION);
    for (const value of await page.locator('.route-facts dd').allInnerTexts()) expect(value.trim()).not.toBe('');
  } finally {
    cleanup([owner.email]);
  }
});

const UNRELIABLE = 'This state may be unreliable.';
const MOTHER_MMSI = '563139001';
const FEEDER_MMSI = '563139002';

function report(mmsi: string, name: string, latitude: string, longitude: string, speed: string, status: string, ingested: string) {
  sql(`INSERT INTO vessel_observations (
      mmsi, vessel_name, latitude, longitude, speed_over_ground_knots,
      navigational_status, position_valid, ingested_at, source)
    VALUES (:'mmsi', :'name', :'latitude', :'longitude', :'speed', :'status', true, :'ingested', 'SEEDED');`, {
    mmsi, name, latitude, longitude, speed, status, ingested,
  });
}

function clearReports() {
  sql(`DELETE FROM vessel_observations WHERE mmsi IN ('${MOTHER_MMSI}', '${FEEDER_MMSI}');`);
}

test('an authorised user sees the operational state change when a newer AIS report arrives', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The detail page is covered once on desktop.');
  test.setTimeout(90_000);
  const owner = await registerAccount(request, 'HARBOURLINE_DEMO');
  try {
    const shipment = await createShipment(request, owner.token);
    await signIn(page, owner.email);
    await expect(page.getByText('Mother vessel state')).toHaveCount(0);

    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(fact(page, 'Mother vessel state')).toHaveText('Unknown');
    await expect(fact(page, 'Feeder vessel state')).toHaveText('Unknown');
    await expect(page.locator('.state-warning')).toHaveCount(0);

    report(MOTHER_MMSI, 'EVER STEADY', '1.264', '103.840', '0.2', '1', '2026-10-14T01:00:00Z');
    report(FEEDER_MMSI, 'STRAITS FEEDER', '1.264', '103.840', '0.1', '5', '2026-10-14T01:00:00Z');
    await page.reload();
    await expect(fact(page, 'Mother vessel state')).toHaveText('At anchor near Singapore');
    await expect(fact(page, 'Feeder vessel state')).toHaveText('Moored near Singapore');
    await expect(page.locator('.state-warning')).toHaveCount(0);

    const listed = await request.get('/api/shipments', { headers: { Authorization: `Bearer ${owner.token}` } });
    expect(listed.ok()).toBeTruthy();
    expect(JSON.stringify(await listed.json())).not.toContain('OperationalState');

    report(MOTHER_MMSI, 'EVER STEADY', '1.264', '103.840', '4.0', '5', '2026-10-14T02:00:00Z');
    await page.reload();
    await expect(fact(page, 'Mother vessel state')).toContainText('Moored near Singapore');
    await expect(fact(page, 'Mother vessel state').locator('.state-warning')).toHaveText(UNRELIABLE);
    await expect(fact(page, 'Feeder vessel state')).toHaveText('Moored near Singapore');
    await expect(fact(page, 'Feeder vessel state').locator('.state-warning')).toHaveCount(0);

    report(MOTHER_MMSI, 'EVER STEADY', '10.0', '110.0', '14.0', '0', '2026-10-14T03:00:00Z');
    await page.reload();
    await expect(fact(page, 'Mother vessel state')).toHaveText('Under way, far from port');
    await expect(fact(page, 'Mother vessel state').locator('.state-warning')).toHaveCount(0);

    report(MOTHER_MMSI, 'EVER STEADY', '1.264', '103.840', '0.2', '15', '2026-10-14T04:00:00Z');
    await page.reload();
    await expect(fact(page, 'Mother vessel state')).toHaveText('Unknown near Singapore');
    await expect(page.locator('.state-warning')).toHaveCount(0);
  } finally {
    clearReports();
    cleanup([owner.email]);
  }
});

test('another company cannot see the operational state', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The detail page is covered once on desktop.');
  const owner = await registerAccount(request, 'HARBOURLINE_DEMO');
  const outsider = await registerAccount(request, 'STRAITS_FRESH_DEMO');
  try {
    const shipment = await createShipment(request, owner.token);
    report(MOTHER_MMSI, 'EVER STEADY', '1.264', '103.840', '0.2', '1', '2026-10-14T01:00:00Z');

    const visible = await request.get(`/api/shipments/${shipment.id}`, {
      headers: { Authorization: `Bearer ${owner.token}` },
    });
    expect(visible.ok()).toBeTruthy();
    expect((await visible.json()).motherOperationalState).toEqual({
      label: 'At anchor', description: 'At anchor near Singapore', unreliable: false,
    });

    const denied = await request.get(`/api/shipments/${shipment.id}`, {
      headers: { Authorization: `Bearer ${outsider.token}` },
    });
    expect(denied.status()).toBe(403);
    const deniedBody = await denied.text();
    expect(deniedBody).not.toContain('operationalState');
    expect(deniedBody).not.toContain('At anchor near Singapore');

    await signIn(page, outsider.email);
    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    await expect(page.getByText(NO_ACCESS)).toBeVisible();
    await expect(page.getByText('Mother vessel state')).toHaveCount(0);
    await expect(page.getByText('At anchor near Singapore')).toHaveCount(0);
    await expect(page.getByText(shipment.shipmentReference)).toHaveCount(0);
  } finally {
    clearReports();
    cleanup([owner.email, outsider.email]);
  }
});
