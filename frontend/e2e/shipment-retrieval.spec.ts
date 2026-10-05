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
    await expect(row.getByRole('cell').nth(1)).toHaveText(shipment.origin);
    await expect(row.getByRole('cell').nth(2)).toHaveText(shipment.destination);
    await expect(row.getByRole('cell').nth(3)).toHaveText(shipment.transshipmentPort);
    await expect(row.getByRole('cell').nth(4)).toHaveText(shipment.motherVessel);
    await expect(row.getByRole('cell').nth(5)).toHaveText(shipment.feederVessel);

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
    await expect(row.getByRole('cell').nth(1)).toHaveText(shipment.origin);
    await expect(row.getByRole('cell').nth(2)).toHaveText(shipment.destination);
    await expect(row.getByRole('cell').nth(3)).toHaveText(shipment.transshipmentPort);
    await expect(row.getByRole('cell').nth(4)).toHaveText(shipment.motherVessel);
    await expect(row.getByRole('cell').nth(5)).toHaveText(shipment.feederVessel);
  } finally {
    sql("DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email'); DELETE FROM invitations WHERE email = :'email'; DELETE FROM users WHERE email = :'email';", { email });
  }
});
