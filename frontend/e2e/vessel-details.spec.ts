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
const NOT_FOUND = 'Shipment not found';
const MOTHER_WARNING = 'The AIS vessel name does not match the stored mother vessel.';
const FEEDER_WARNING = 'The AIS vessel name does not match the stored feeder vessel.';
const NOT_AVAILABLE = 'Not available';
const ARRIVAL = '2026-10-15T08:00:00+08:00';
const DEPARTURE = '2026-10-16T12:00:00+08:00';
const ARRIVAL_STORED = '2026-10-15T00:00:00Z';
const DEPARTURE_STORED = '2026-10-16T04:00:00Z';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg147_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:vessel-details. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the vessel details runner. Refusing to write fixtures.');
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

function digits(length: number, lead: number) {
  const span = length - 1;
  const n = randomBytes(4).readUInt32BE(0) % (10 ** span);
  return `${lead}${String(n).padStart(span, '0')}`;
}

type Account = { email: string; token: string; home: string };

async function registerAccount(
  request: APIRequestContext,
  role: 'IMPORTER' | 'FREIGHT_FORWARDER',
  company: string,
): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg147-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName: 'Alex Tan', email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  return {
    email,
    token: (await signedIn.json()).token as string,
    home: role === 'IMPORTER' ? '/importer' : '/freight-forwarder',
  };
}

type Detail = {
  id: number;
  shipmentReference: string;
  motherVessel: string;
  feederVessel: string | null;
  plannedMotherArrivalAt: string;
  plannedFeederDepartureAt: string;
  motherVesselImo: string | null;
  motherVesselMmsi: string | null;
  motherVesselOperator: string | null;
  feederVesselImo: string | null;
  feederVesselMmsi: string | null;
  feederVesselOperator: string | null;
  motherVesselNameWarning: string | null;
  feederVesselNameWarning: string | null;
};

async function createShipment(request: APIRequestContext, token: string, body: Record<string, string>) {
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: body,
  });
  expect(response.status(), await response.text()).toBe(201);
  const created = await response.json() as Record<string, unknown>;
  expect(created.motherVesselImo).toBeUndefined();
  expect(created.feederVesselOperator).toBeUndefined();
  expect(created.motherVesselNameWarning).toBeUndefined();
  const detail = await request.get(`/api/shipments/${created.id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(detail.status(), await detail.text()).toBe(200);
  return await detail.json() as Detail;
}

function cleanup(emails: string[], identifiers: string[] = []) {
  for (const email of emails) {
    sql(`DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
  for (const identifier of identifiers) {
    sql("DELETE FROM vessel_observations WHERE mmsi = :'mmsi';", { mmsi: identifier });
  }
}

function seedObservation(vessel: string, identifier: string) {
  sql(`INSERT INTO vessel_observations (
      mmsi, vessel_name, latitude, longitude, speed_over_ground_knots,
      course_over_ground_degrees, true_heading_degrees, navigational_status,
      position_valid, ais_utc_second, ingested_at, source
    ) VALUES (:'mmsi', :'vessel', 1.264, 103.820, 12.4, 175, 176, 0, true, 12, NOW(), 'SEEDED');`,
    { mmsi: identifier, vessel });
}

async function signIn(page: Page, account: Account) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(account.home);
}

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL('/login');
}

function fact(page: Page, label: string) {
  return page.locator('.route-facts dl > div').filter({ has: page.getByText(label, { exact: true }) }).locator('dd');
}

async function expectStoredIdentity(page: Page, detail: Detail) {
  await expect(page.getByRole('heading', { name: detail.shipmentReference })).toBeVisible();
  await expect(fact(page, 'Mother vessel')).toHaveText(detail.motherVessel);
  await expect(fact(page, 'Feeder vessel')).toHaveText(detail.feederVessel ?? 'Pending assignment');
  for (const [label, value] of [
    ['Mother vessel IMO', detail.motherVesselImo],
    ['Mother vessel MMSI', detail.motherVesselMmsi],
    ['Mother vessel operator', detail.motherVesselOperator],
    ['Feeder vessel IMO', detail.feederVesselImo],
    ['Feeder vessel MMSI', detail.feederVesselMmsi],
    ['Feeder vessel operator', detail.feederVesselOperator],
  ] as const) {
    const cell = fact(page, label);
    await expect(cell).toHaveText(value ?? NOT_AVAILABLE);
    if (value) await expect(cell).not.toHaveClass(/is-missing/);
    else await expect(cell).toHaveClass(/is-missing/);
  }
  await expect(fact(page, 'Planned arrival').locator('time')).toHaveAttribute('datetime', detail.plannedMotherArrivalAt);
  await expect(fact(page, 'Planned departure').locator('time')).toHaveAttribute('datetime', detail.plannedFeederDepartureAt);
  await expect(fact(page, 'Mother vessel').locator('.vessel-name-warning')).toHaveCount(0);
  await expect(fact(page, 'Feeder vessel').locator('.vessel-name-warning')).toHaveCount(0);
  await expect(page.locator('article.shipment-record input, article.shipment-record textarea, article.shipment-record select')).toHaveCount(0);
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('an authorised user sees the persisted vessel identity and planned schedule', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(90_000);
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const importer = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO');
  const token = randomBytes(3).toString('hex');
  try {
    const detail = await createShipment(request, owner.token, {
      shipmentReference: `CDG147-${token}`,
      origin: `Rotterdam ${token}`,
      destination: `Jakarta ${token}`,
      transshipmentPort: `Singapore ${token}`,
      motherVessel: `MV Cedar ${token}`,
      plannedMotherArrivalAt: ARRIVAL,
      feederVessel: `MV Relay ${token}`,
      plannedFeederDepartureAt: DEPARTURE,
      motherVesselImo: digits(7, 9),
      motherVesselMmsi: digits(9, 5),
      motherVesselOperator: `Cedar Line ${token}`,
      feederVesselImo: digits(7, 4),
      feederVesselMmsi: digits(9, 6),
      feederVesselOperator: `Relay Line ${token}`,
    });
    expect(detail.plannedMotherArrivalAt).toBe(ARRIVAL_STORED);
    expect(detail.plannedFeederDepartureAt).toBe(DEPARTURE_STORED);
    expect(detail.motherVessel).toBe(`MV Cedar ${token}`);
    expect(detail.feederVessel).toBe(`MV Relay ${token}`);
    expect(detail.motherVesselOperator).toBe(`Cedar Line ${token}`);
    expect(detail.feederVesselOperator).toBe(`Relay Line ${token}`);
    expect(detail.motherVesselNameWarning).toBeNull();
    expect(detail.feederVesselNameWarning).toBeNull();

    const list = await request.get('/api/shipments', { headers: { Authorization: `Bearer ${owner.token}` } });
    expect(list.status()).toBe(200);
    const row = (await list.json() as Record<string, unknown>[]).find(item => item.id === detail.id);
    expect(row?.motherVessel).toBe(`MV Cedar ${token}`);
    expect(row).not.toHaveProperty('motherVesselImo');
    expect(row).not.toHaveProperty('motherVesselNameWarning');

    await signIn(page, owner);
    await page.getByRole('link', { name: detail.shipmentReference }).click();
    await expect(page).toHaveURL(`/freight-forwarder/shipments/${detail.id}`);
    for (const open of [async () => {}, () => page.reload()]) {
      await open();
      await expectStoredIdentity(page, detail);
      await expect(fact(page, 'Origin')).toHaveText(`Rotterdam ${token}`);
      await expect(fact(page, 'Destination')).toHaveText(`Jakarta ${token}`);
      await expect(fact(page, 'Transshipment port')).toHaveText(`Singapore ${token}`);
    }

    const importerCompanyId = sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO';");
    const linked = await request.patch(`/api/shipments/${detail.id}/link-importer`, {
      headers: { Authorization: `Bearer ${owner.token}` },
      data: { importerCompanyId: Number(importerCompanyId) },
    });
    expect(linked.status(), await linked.text()).toBe(200);
    expect((await linked.json()).motherVesselImo).toBe(detail.motherVesselImo);

    await signOut(page);
    await signIn(page, importer);
    await page.getByRole('link', { name: detail.shipmentReference }).click();
    await expect(page).toHaveURL(`/importer/shipments/${detail.id}`);
    await expectStoredIdentity(page, detail);
  } finally {
    cleanup([owner.email, importer.email]);
  }
});

test('a missing IMO, MMSI, or operator is shown as Not available', { tag: '@disposable-database' }, async ({ page, request }) => {
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const token = randomBytes(3).toString('hex');
  try {
    const detail = await createShipment(request, owner.token, {
      shipmentReference: `CDG147-MISS-${token}`,
      origin: 'Rotterdam',
      destination: 'Jakarta',
      transshipmentPort: 'Singapore',
      motherVessel: `MV Cedar ${token}`,
      plannedMotherArrivalAt: ARRIVAL,
      feederVessel: `MV Relay ${token}`,
      plannedFeederDepartureAt: DEPARTURE,
    });
    expect(detail.motherVesselImo).toBeNull();
    expect(detail.motherVesselMmsi).toBeNull();
    expect(detail.motherVesselOperator).toBeNull();
    expect(detail.feederVesselImo).toBeNull();
    expect(detail.feederVesselMmsi).toBeNull();
    expect(detail.feederVesselOperator).toBeNull();

    await signIn(page, owner);
    await page.goto(`/freight-forwarder/shipments/${detail.id}`);
    await expectStoredIdentity(page, detail);
    await expect(page.getByText(NOT_AVAILABLE, { exact: true })).toHaveCount(6);
    for (const value of await page.locator('.route-facts dd').allInnerTexts()) expect(value.trim()).not.toBe('');
  } finally {
    cleanup([owner.email]);
  }
});

test('a stored vessel name that differs from the latest AIS name shows the confirmation warning', { tag: '@disposable-database' }, async ({ page, request }) => {
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const token = randomBytes(3).toString('hex');
  const motherMmsi = digits(9, 5);
  const feederMmsi = digits(9, 6);
  const aisMother = digits(9, 7);
  const aisFeeder = digits(9, 8);
  const motherAisName = `CEDAR ${token}`;
  const feederAisName = `mv   relay   ${token}`;
  try {
    const detail = await createShipment(request, owner.token, {
      shipmentReference: `CDG147-AIS-${token}`,
      origin: 'Rotterdam',
      destination: 'Jakarta',
      transshipmentPort: 'Singapore',
      motherVessel: `MV Cedar ${token}`,
      plannedMotherArrivalAt: ARRIVAL,
      feederVessel: `MV Relay ${token}`,
      plannedFeederDepartureAt: DEPARTURE,
      motherVesselImo: digits(7, 9),
      motherVesselMmsi: motherMmsi,
      motherVesselOperator: `Cedar Line ${token}`,
      feederVesselImo: digits(7, 4),
      feederVesselMmsi: feederMmsi,
      feederVesselOperator: `Relay Line ${token}`,
    });
    seedObservation(motherAisName, aisMother);
    seedObservation(feederAisName, aisFeeder);

    const trackingResponse = await request.get(`/api/shipments/${detail.id}/tracking`, {
      headers: { Authorization: `Bearer ${owner.token}` },
    });
    expect(trackingResponse.status()).toBe(200);
    const tracking = await trackingResponse.json() as {
      motherVessel: { vesselName: string; mmsi: string };
      feederVessel: { vesselName: string; mmsi: string };
      motherVesselNameWarning: string | null;
      feederVesselNameWarning: string | null;
    };
    expect(tracking.motherVessel.vesselName).toBe(motherAisName);
    expect(tracking.motherVessel.mmsi).toBe(aisMother);
    expect(tracking.motherVesselNameWarning).toBe(MOTHER_WARNING);
    expect(tracking.feederVessel.vesselName).toBe(feederAisName);
    expect(tracking.feederVessel.mmsi).toBe(aisFeeder);
    expect(tracking.feederVesselNameWarning).toBeNull();

    const refreshed = await request.get(`/api/shipments/${detail.id}`, {
      headers: { Authorization: `Bearer ${owner.token}` },
    });
    const refreshedBody = await refreshed.json() as Detail;
    expect(refreshedBody.motherVesselNameWarning).toBe(MOTHER_WARNING);
    expect(refreshedBody.feederVesselNameWarning).toBeNull();
    expect(refreshedBody.motherVesselMmsi).toBe(motherMmsi);

    await signIn(page, owner);
    const tracked = page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/shipments/${detail.id}/tracking`);
    await page.goto(`/freight-forwarder/shipments/${detail.id}`);
    expect((await tracked).ok()).toBe(true);

    await expect(fact(page, 'Mother vessel').locator('span').first()).toHaveText(`MV Cedar ${token}`);
    await expect(fact(page, 'Mother vessel').locator('.vessel-name-warning')).toHaveText(MOTHER_WARNING);
    await expect(fact(page, 'Feeder vessel').locator('span').first()).toHaveText(`MV Relay ${token}`);
    await expect(fact(page, 'Feeder vessel').locator('.vessel-name-warning')).toHaveCount(0);
    await expect(page.getByText(FEEDER_WARNING)).toHaveCount(0);
    await expect(fact(page, 'Mother vessel position')).toContainText(`${motherAisName} · 1.264, 103.82 · 12.4 kn`);
    await expect(fact(page, 'Feeder vessel position')).toContainText(`mv relay ${token} · 1.264, 103.82 · 12.4 kn`);
    await expect(fact(page, 'Mother vessel MMSI')).toHaveText(motherMmsi);
    await expect(fact(page, 'Feeder vessel MMSI')).toHaveText(feederMmsi);
    await expect(page.getByText(aisMother, { exact: true })).toHaveCount(0);
    await expect(page.getByText(aisFeeder, { exact: true })).toHaveCount(0);
  } finally {
    cleanup([owner.email], [aisMother, aisFeeder]);
  }
});

test('a user who cannot view the shipment does not receive the vessel details', { tag: '@disposable-database' }, async ({ page, request }) => {
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const outsider = await registerAccount(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO');
  const token = randomBytes(3).toString('hex');
  try {
    const detail = await createShipment(request, owner.token, {
      shipmentReference: `CDG147-DENY-${token}`,
      origin: 'Rotterdam',
      destination: 'Jakarta',
      transshipmentPort: 'Singapore',
      motherVessel: `MV Cedar ${token}`,
      plannedMotherArrivalAt: ARRIVAL,
      feederVessel: `MV Relay ${token}`,
      plannedFeederDepartureAt: DEPARTURE,
      motherVesselImo: digits(7, 9),
      motherVesselMmsi: digits(9, 5),
      motherVesselOperator: `Cedar Line ${token}`,
      feederVesselImo: digits(7, 4),
      feederVesselMmsi: digits(9, 6),
      feederVesselOperator: `Relay Line ${token}`,
    });

    const stillThere = await request.get(`/api/shipments/${detail.id}`, {
      headers: { Authorization: `Bearer ${owner.token}` },
    });
    expect(stillThere.status(), await stillThere.text()).toBe(200);

    const deniedDetail = await request.get(`/api/shipments/${detail.id}`, {
      headers: { Authorization: `Bearer ${outsider.token}` },
    });
    const deniedDetailBody = await deniedDetail.text();
    expect(deniedDetail.status(), deniedDetailBody).toBe(403);
    expect(JSON.parse(deniedDetailBody)).toEqual({ message: NO_ACCESS });
    expect(deniedDetailBody).not.toContain(detail.motherVesselImo);
    expect(deniedDetailBody).not.toContain(detail.shipmentReference);
    expect(deniedDetailBody).not.toContain(`MV Cedar ${token}`);

    const deniedTracking = await request.get(`/api/shipments/${detail.id}/tracking`, {
      headers: { Authorization: `Bearer ${outsider.token}` },
    });
    const deniedTrackingBody = await deniedTracking.text();
    expect(deniedTracking.status(), deniedTrackingBody).toBe(404);
    expect(JSON.parse(deniedTrackingBody)).toEqual({ message: NOT_FOUND });
    expect(deniedTrackingBody).not.toContain(detail.motherVesselImo);
    expect(deniedTrackingBody).not.toContain(detail.shipmentReference);
    expect(deniedTrackingBody).not.toContain(`MV Cedar ${token}`);

    const listResponse = page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/shipments');
    await signIn(page, outsider);
    expect((await (await listResponse).json()).map((item: { id: number }) => item.id)).not.toContain(detail.id);
    await page.goto(`/freight-forwarder/shipments/${detail.id}`);
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    await expect(page.getByText(NO_ACCESS)).toBeVisible();
    await expect(page.getByText(detail.shipmentReference)).toHaveCount(0);
    await expect(page.getByText(`MV Cedar ${token}`)).toHaveCount(0);
    await expect(page.getByText(detail.motherVesselImo ?? '')).toHaveCount(0);
    await expect(page.getByText(detail.feederVesselOperator ?? '')).toHaveCount(0);
    await expect(page.locator('.route-facts')).toHaveCount(0);
  } finally {
    cleanup([owner.email, outsider.email]);
  }
});
