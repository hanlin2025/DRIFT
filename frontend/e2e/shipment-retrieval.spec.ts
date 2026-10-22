import { test, expect, type APIRequestContext, type Response } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg59_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:shipment. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the shipment retrieval runner. Refusing to write fixtures.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'drift', '-d', database,
    '-v', 'ON_ERROR_STOP=1', '-At', ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])],
    { cwd: root, input: query, encoding: 'utf8' }).trim();
}

function shipmentCollection(response: Response, method: string) {
  return response.request().method() === method && new URL(response.url()).pathname === '/api/shipments';
}

const password = 'Example123';

async function registerFreightForwarder(request: APIRequestContext, email: string) {
  const token = randomBytes(32).toString('base64url');
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = 'HARBOURLINE_DEMO';`, { email, hash: createHash('sha256').update(token).digest('hex') });
  const response = await request.post('/api/register', { data: { fullName: 'Alex Tan', email, password, invitationToken: token } });
  expect(response.status(), 'registration must reach the disposable database').toBe(201);
}

test('a freight forwarder sees one created shipment after refresh and reload', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The retrieval flow is covered once on desktop.');
  assertOwnedDatabase();
  const email = `cdg59-e2e-${randomBytes(8).toString('hex')}@example.com`;
  const shipment = {
    shipmentReference: `CDG59-${randomBytes(4).toString('hex')}`,
    origin: 'Rotterdam',
    destination: 'Jakarta',
    transshipmentPort: 'Singapore',
    motherVessel: 'Ever Steady',
    feederVessel: 'Straits Feeder',
  };
  const creates: number[] = [];
  page.on('response', response => {
    if (shipmentCollection(response, 'POST')) creates.push(response.status());
  });
  try {
    await registerFreightForwarder(request, email);
    expect(sql("SELECT COUNT(*) FROM shipments WHERE company_id = (SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO');")).toBe('0');
    const initialList = page.waitForResponse(response => shipmentCollection(response, 'GET'));
    await page.goto('/login');
    await page.getByLabel('Work email').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /Log in/ }).click();
    await expect(page).toHaveURL('/freight-forwarder');
    const initial = await initialList;
    expect(initial.status()).toBe(200);
    expect(await initial.json()).toEqual([]);
    await expect(page.getByText('No shipments yet. Shipments registered for your company will appear here.')).toBeVisible();

    await page.getByLabel('Shipment reference').fill(shipment.shipmentReference);
    await page.getByLabel('Origin', { exact: true }).fill(shipment.origin);
    await page.getByLabel('Destination', { exact: true }).fill(shipment.destination);
    await page.getByLabel('Transshipment port').fill(shipment.transshipmentPort);
    await page.getByLabel('Mother vessel').fill(shipment.motherVessel);
    await page.getByLabel('Feeder vessel').fill(shipment.feederVessel);
    await page.getByLabel('Planned mother-vessel arrival').fill('2026-10-15T09:00');
    await page.getByLabel('Planned feeder-vessel departure').fill('2026-10-15T18:00');

    const createdResponse = page.waitForResponse(response => shipmentCollection(response, 'POST'));
    const refreshedList = page.waitForResponse(response => shipmentCollection(response, 'GET'));
    await page.getByRole('button', { name: 'Register shipment' }).click();
    const created = await createdResponse;
    const refreshed = await refreshedList;
    expect(created.status()).toBe(201);
    expect(creates).toEqual([201]);
    expect(await created.json()).toMatchObject(shipment);
    expect(refreshed.status()).toBe(200);
    const listed = await refreshed.json();
    expect(listed).toHaveLength(1);
    expect(listed[0]).toMatchObject(shipment);

    const row = page.locator('tbody tr');
    await expect(row).toHaveCount(1);
    await expect(row.getByRole('cell').nth(0)).toHaveText(shipment.shipmentReference);
    await expect(row.getByRole('cell').nth(3)).toHaveText(shipment.origin);
    await expect(row.getByRole('cell').nth(4)).toHaveText(shipment.destination);
    await expect(row.getByRole('cell').nth(5)).toHaveText(shipment.transshipmentPort);
    await expect(row.getByRole('cell').nth(6)).toHaveText(shipment.motherVessel);
    await expect(row.getByRole('cell').nth(7)).toHaveText(shipment.feederVessel);

    const reloadedList = page.waitForResponse(response => shipmentCollection(response, 'GET'));
    await page.reload();
    const reloaded = await reloadedList;
    expect(reloaded.status()).toBe(200);
    const persisted = await reloaded.json();
    expect(persisted).toHaveLength(1);
    expect(persisted[0]).toMatchObject(shipment);
    expect(creates).toEqual([201]);
    await expect(row).toHaveCount(1);
    await expect(row.getByRole('cell').nth(0)).toHaveText(shipment.shipmentReference);
    await expect(row.getByRole('cell').nth(3)).toHaveText(shipment.origin);
    await expect(row.getByRole('cell').nth(4)).toHaveText(shipment.destination);
    await expect(row.getByRole('cell').nth(5)).toHaveText(shipment.transshipmentPort);
    await expect(row.getByRole('cell').nth(6)).toHaveText(shipment.motherVessel);
    await expect(row.getByRole('cell').nth(7)).toHaveText(shipment.feederVessel);
  } finally {
    sql("DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email'); DELETE FROM invitations WHERE email = :'email'; DELETE FROM users WHERE email = :'email';", { email });
  }
});

type ListedShipment = { id: number; shipmentReference: string; risk: { level: string; explanation: string } | null; importer: { name: string } | null };

function shipmentList(response: Response, parameters: Record<string, string>) {
  const url = new URL(response.url());
  return shipmentCollection(response, 'GET') && Object.entries(parameters).every(([key, value]) => url.searchParams.get(key) === value);
}

function hoursFromNow(hours: number) {
  return new Date(Date.now() + hours * 3_600_000).toISOString();
}

test('a freight forwarder prioritises at-risk shipments across two importers', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The prioritisation flow is covered once on desktop.');
  assertOwnedDatabase();
  const suffix = randomBytes(4).toString('hex');
  const email = `cdg192-e2e-${suffix}@example.com`;
  const secondImporter = { code: `CDG192_${suffix.toUpperCase()}`, name: `Lion City Grocers ${suffix}` };
  const lateMother = `CDG192 Late Mother ${suffix}`;
  const lateMmsi = `6${randomBytes(4).readUInt32BE(0).toString().padStart(8, '0').slice(0, 8)}`;
  const reference = (level: string) => `CDG192-${level}-${suffix}`;
  try {
    await registerFreightForwarder(request, email);
    sql("INSERT INTO companies (code, name, active) VALUES (:'code', :'name', TRUE);", secondImporter);
    // The schedule check stops a stored feeder departing at or before the mother arrives and the feeder departure is
    // required, so stored shipments are never "Not assessed". CRITICAL comes from a fresh AIS position about 480 nm out
    // at 5 knots, whose estimated arrival is long after the feeder leaves. "Not assessed" is covered by unit tests.
    const rows: Array<[string, string, string, number, number, string]> = [
      [reference('LOW'), 'CDG192 Mother', 'HARBOURLINE_DEMO', 24, 48, secondImporter.code],
      [reference('MEDIUM'), 'CDG192 Mother', 'HARBOURLINE_DEMO', 24, 18, 'STRAITS_FRESH_DEMO'],
      [reference('HIGH'), 'CDG192 Mother', 'HARBOURLINE_DEMO', 12, 8, secondImporter.code],
      [reference('CRITICAL'), lateMother, 'HARBOURLINE_DEMO', 24, 30, 'STRAITS_FRESH_DEMO'],
    ];
    for (const [shipmentReference, motherVessel, owner, arrivalInHours, gapHours, importer] of rows) {
      sql(`INSERT INTO shipments (
          company_id, importer_company_id, created_by_user_id, updated_by_user_id, shipment_reference, origin,
          destination, transshipment_port, mother_vessel, planned_mother_arrival_at, feeder_vessel,
          planned_feeder_departure_at, created_at, updated_at, version)
        SELECT owner.id, importer.id, creator.id, creator.id, :'reference', 'Jakarta', 'Rotterdam', 'Tanjung Pelepas',
          :'mother', :'arrival'::timestamptz, 'CDG192 Feeder', :'departure'::timestamptz, NOW(), NOW(), 0
        FROM companies owner, companies importer, users creator
        WHERE owner.code = :'owner' AND importer.code = :'importer' AND creator.email = :'email';`, {
        reference: shipmentReference, mother: motherVessel, owner, importer, email,
        arrival: hoursFromNow(arrivalInHours), departure: hoursFromNow(arrivalInHours + gapHours),
      });
    }
    sql(`INSERT INTO vessel_observations (mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
      VALUES (:'mmsi', :'vessel', 8.5, 100.25, 5, NOW(), 'SEEDED');`, { mmsi: lateMmsi, vessel: lateMother });

    const initialList = page.waitForResponse(response => shipmentCollection(response, 'GET'));
    await page.goto('/login');
    await page.getByLabel('Work email').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /Log in/ }).click();
    await expect(page).toHaveURL('/freight-forwarder');
    const listed = await (await initialList).json() as ListedShipment[];
    expect(listed.map(item => item.risk?.level).sort()).toEqual(['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']);

    const tableRows = page.locator('tbody tr');
    await expect(tableRows).toHaveCount(4);
    for (const item of listed) {
      const row = tableRows.filter({ has: page.getByRole('link', { name: item.shipmentReference }) });
      const label = item.risk!.level.charAt(0) + item.risk!.level.slice(1).toLowerCase();
      await expect(row.getByRole('cell').nth(1).locator('.risk-badge')).toHaveText(label);
      await expect(row.getByRole('cell').nth(2)).toHaveText(item.importer!.name);
    }
    await expect(tableRows.filter({ hasText: secondImporter.name })).toHaveCount(2);
    await expect(tableRows.filter({ hasText: 'Straits Fresh' })).toHaveCount(2);

    const byRisk = page.waitForResponse(response => shipmentList(response, { sort: 'risk' }));
    await page.getByRole('combobox', { name: 'Sort by' }).selectOption('risk');
    await page.getByRole('button', { name: 'Apply filters' }).click();
    expect((await byRisk).status()).toBe(200);
    await expect(page).toHaveURL(/sort=risk/);
    await expect(tableRows.locator('td:first-child')).toHaveText(
      [reference('CRITICAL'), reference('HIGH'), reference('MEDIUM'), reference('LOW')]);

    const atRisk = page.waitForResponse(response => shipmentList(response, { sort: 'risk', atRisk: 'true' }));
    await page.getByRole('checkbox', { name: 'At-risk only' }).check();
    await page.getByRole('button', { name: 'Apply filters' }).click();
    const atRiskRows = await (await atRisk).json() as ListedShipment[];
    expect(atRiskRows.map(item => item.risk?.level)).toEqual(['CRITICAL', 'HIGH']);
    await expect(tableRows.locator('td:first-child')).toHaveText([reference('CRITICAL'), reference('HIGH')]);

    const critical = atRiskRows.find(item => item.shipmentReference === reference('CRITICAL'))!;
    await page.getByRole('link', { name: reference('CRITICAL') }).click();
    const record = page.getByRole('article', { name: `Shipment ${reference('CRITICAL')}` });
    await expect(record.locator('.risk-badge')).toHaveText('Critical');
    await expect(record.getByText(critical.risk!.explanation)).toBeVisible();
  } finally {
    sql(`DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM vessel_observations WHERE mmsi = :'mmsi';
      DELETE FROM invitations WHERE email = :'email'; DELETE FROM users WHERE email = :'email';
      DELETE FROM companies WHERE code = :'code';`, { email, mmsi: lateMmsi, code: secondImporter.code });
  }
});
