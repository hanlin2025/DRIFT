import { test, expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const INVALID = 'Invalid or inactive importer organisation selected.';
const LINK_FORBIDDEN = "Only a freight forwarder in the shipment's company can link it to an importer";
const MISSING_SHIPMENT = 'Shipment information is missing or invalid';
const SESSION_ENDED = 'Your session has ended. Log in again.';
const NOT_FOUND = 'Shipment not found';
const NO_ACCESS = 'You do not have access to this shipment';
const VIEW_COMPANY = 'Your account must belong to an active company to view shipments';
const EMPTY_LIST = 'No shipments yet. Shipments registered for your company will appear here.';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg124_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through node ../scripts/run-shipment-linking.mjs. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the shipment linking runner. Refusing to write fixtures.');
  }
}

const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function psqlInvocation(extra: string[]) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', ...extra];
  if (useDocker) {
    return { command: 'docker', args: ['compose', 'exec', '-T', 'postgres', 'psql', ...common], env: process.env };
  }
  return {
    command: 'psql',
    args: ['-h', '127.0.0.1', ...common],
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  };
}

function sql(query: string, variables: Record<string, string> = {}) {
  const variableArgs = Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`]);
  const invocation = psqlInvocation(variableArgs);
  return execFileSync(invocation.command, invocation.args, {
    cwd: root, input: query, encoding: 'utf8', env: invocation.env,
  }).trim();
}

type Account = {
  email: string;
  token: string;
  id: number;
  fullName: string;
  companyId: number | null;
  role: string;
  companyCode: string | null;
};

type Shipment = { id: number; shipmentReference: string; vessel: string; version: number };

function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function expectMessage(response: APIResponse, status: number, message: string) {
  const body = await response.text();
  expect(response.status(), body).toBe(status);
  expect(JSON.parse(body)).toEqual({ message });
}

async function registerAccount(
  request: APIRequestContext,
  role: 'FREIGHT_FORWARDER' | 'IMPORTER',
  company: string,
  fullName: string,
): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg124-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName, email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  return signInApi(request, email);
}

async function signInApi(request: APIRequestContext, email: string): Promise<Account> {
  const signedIn = await request.post('/api/login', { data: { email, password } });
  const bodyText = await signedIn.text();
  expect(signedIn.status(), bodyText).toBe(200);
  const body = JSON.parse(bodyText);
  expect(body.token).toEqual(expect.any(String));
  expect(body.id).toEqual(expect.any(Number));
  if (body.company != null) expect(body.company.id).toEqual(expect.any(Number));
  return {
    email,
    token: body.token,
    id: body.id,
    fullName: body.fullName,
    companyId: body.company?.id ?? null,
    role: body.role,
    companyCode: body.company?.code ?? null,
  };
}

function setRole(email: string, role: string) {
  sql(`UPDATE users SET role_id = (SELECT id FROM roles WHERE code = :'role') WHERE email = :'email';`, { email, role });
}

function clearCompany(email: string) {
  sql(`UPDATE users SET company_id = NULL WHERE email = :'email';`, { email });
}

function companyId(code: string) {
  return Number(sql('SELECT id FROM companies WHERE code = :\'code\';', { code }));
}

function insertCompany(code: string, name: string, active: boolean) {
  sql(`INSERT INTO companies (code, name, active) VALUES (:'code', :'name', ${active ? 'TRUE' : 'FALSE'});`, { code, name });
}

function shipmentData(shipmentReference: string, vessel: string, version?: number) {
  return {
    shipmentReference,
    origin: 'Rotterdam',
    destination: 'Jakarta',
    transshipmentPort: 'Singapore',
    motherVessel: vessel,
    plannedMotherArrivalAt: '2026-10-15T08:00:00+08:00',
    feederVessel: 'Straits Feeder',
    plannedFeederDepartureAt: '2026-10-16T12:00:00+08:00',
    ...(version === undefined ? {} : { version }),
  };
}

async function createShipment(request: APIRequestContext, token: string, vessel: string): Promise<Shipment> {
  const shipmentReference = `CDG124-${randomBytes(4).toString('hex')}`;
  const response = await request.post('/api/shipments', {
    headers: bearer(token),
    data: shipmentData(shipmentReference, vessel),
  });
  const body = await response.text();
  expect(response.status(), body).toBe(201);
  const created = JSON.parse(body);
  return { id: created.id as number, shipmentReference, vessel, version: created.version as number };
}

async function linkImporter(request: APIRequestContext, token: string | null, shipmentId: string | number, importerCompanyId: number | null) {
  return request.patch(`/api/shipments/${shipmentId}/link-importer`, {
    ...(token ? { headers: bearer(token) } : {}),
    data: { importerCompanyId },
  });
}

async function searchImporters(request: APIRequestContext, token: string, name: string) {
  const response = await request.get(`/api/organisations?type=importer&name=${encodeURIComponent(name)}&page=0&size=20`, {
    headers: bearer(token),
  });
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  return JSON.parse(body) as { items: { id: number; code: string; name: string }[] };
}

async function expectHidden(request: APIRequestContext, token: string, shipment: Shipment) {
  const list = await request.get('/api/shipments', { headers: bearer(token) });
  const listBody = await list.text();
  expect(list.status(), listBody).toBe(200);
  expect(listBody).not.toContain(shipment.shipmentReference);
  expect(listBody).not.toContain(shipment.vessel);
  const detail = await request.get(`/api/shipments/${shipment.id}`, { headers: bearer(token) });
  const detailBody = await detail.text();
  expect(detail.status(), detailBody).toBe(403);
  expect(JSON.parse(detailBody)).toEqual({ message: NO_ACCESS });
  expect(detailBody).not.toContain(shipment.vessel);
  await expectMessage(await request.get(`/api/shipments/${shipment.id}/tracking`, { headers: bearer(token) }), 404, NOT_FOUND);
  await expectMessage(await request.put(`/api/shipments/${shipment.id}`, {
    headers: bearer(token),
    data: shipmentData(shipment.shipmentReference, shipment.vessel, shipment.version),
  }), 404, NOT_FOUND);
}

function cleanup(emails: string[], companies: string[] = []) {
  for (const email of emails) {
    sql(`DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
  for (const code of companies) {
    sql(`DELETE FROM companies WHERE code = :'code'
      AND NOT EXISTS (SELECT 1 FROM users WHERE company_id = companies.id)
      AND NOT EXISTS (SELECT 1 FROM invitations WHERE company_id = companies.id)
      AND NOT EXISTS (SELECT 1 FROM shipments WHERE company_id = companies.id OR importer_company_id = companies.id);`, { code });
  }
}

async function signIn(page: Page, email: string, url: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(url);
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('a freight forwarder links a shipment and the importer dashboard shows it at once', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Shipment linking is covered once on desktop.');
  test.setTimeout(120_000);
  const suffix = randomBytes(3).toString('hex');
  const pacificCode = `CDG124P${suffix}`.slice(0, 50);
  insertCompany(pacificCode, `Pacific Gate ${suffix}`, true);
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const straits = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const pacific = await registerAccount(request, 'IMPORTER', pacificCode, 'Casey Pacific');
  const ownedByStraits = await createShipment(request, straits.token, `Straits Own ${suffix}`);
  const shipment = await createShipment(request, forwarder.token, `Ever Steady ${suffix}`);
  try {
    const found = await searchImporters(request, forwarder.token, 'Straits Fresh');
    const straitsOrg = found.items.find(item => item.code === 'STRAITS_FRESH_DEMO');
    expect(straitsOrg?.name).toBe('Straits Fresh Imports (Demo)');
    expect(found.items.map(item => item.code)).not.toContain('HARBOURLINE_DEMO');

    await signIn(page, straits.email, '/importer');
    await expect(page.getByRole('link', { name: ownedByStraits.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toHaveCount(0);
    await expect(page.getByLabel('Link importer')).toHaveCount(0);

    const linked = await linkImporter(request, forwarder.token, shipment.id, straitsOrg!.id);
    const linkedBody = await linked.text();
    expect(linked.status(), linkedBody).toBe(200);
    const linkedJson = JSON.parse(linkedBody);
    expect(linkedJson.importer).toEqual({
      id: straitsOrg!.id,
      code: 'STRAITS_FRESH_DEMO',
      name: 'Straits Fresh Imports (Demo)',
    });

    await page.reload();
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: ownedByStraits.shipmentReference })).toBeVisible();
    await page.goto(`/importer/shipments/${shipment.id}`);
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page.getByText(shipment.vessel).first()).toBeVisible();
    await expect(page.getByText('No live AIS position').first()).toBeVisible();

    const tracking = await request.get(`/api/shipments/${shipment.id}/tracking`, { headers: bearer(straits.token) });
    const trackingBody = await tracking.text();
    expect(tracking.status(), trackingBody).toBe(200);
    const trackingJson = JSON.parse(trackingBody) as { motherVesselName: string; motherVessel: unknown };
    expect(trackingJson.motherVesselName).toBe(shipment.vessel);
    expect(trackingJson.motherVessel).toBeNull();
    await expectMessage(await request.put(`/api/shipments/${shipment.id}`, {
      headers: bearer(straits.token),
      data: shipmentData(shipment.shipmentReference, shipment.vessel, linkedJson.version as number),
    }), 403, NO_ACCESS);

    await expectHidden(request, pacific.token, shipment);
    await expectHidden(request, pacific.token, ownedByStraits);
    await signIn(page, pacific.email, '/importer');
    await expect(page.getByText(EMPTY_LIST)).toBeVisible();
    await expect(page.getByLabel('Link importer')).toHaveCount(0);
    await page.goto(`/importer/shipments/${shipment.id}`);
    await expect(page.getByRole('alert')).toHaveText(NO_ACCESS);
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toHaveCount(0);

    await signIn(page, forwarder.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page.locator('.company-card strong')).toHaveText('Harbourline Logistics (Demo)');
  } finally {
    cleanup([forwarder.email, straits.email, pacific.email], [pacificCode]);
  }
});

test('an invalid, inactive, or missing importer is rejected and the current link stays', { tag: '@disposable-database' }, async ({ request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Shipment linking is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const dormantCode = `CDG124D${suffix}`.slice(0, 50);
  insertCompany(dormantCode, `Dormant Imports ${suffix}`, false);
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const shipment = await createShipment(request, forwarder.token, `Ever Steady ${suffix}`);
  try {
    const dormantSearch = await searchImporters(request, forwarder.token, `Dormant Imports ${suffix}`);
    expect(dormantSearch.items).toEqual([]);
    const ownSearch = await searchImporters(request, forwarder.token, 'Harbourline');
    expect(ownSearch.items.map(item => item.code)).not.toContain('HARBOURLINE_DEMO');

    await expectMessage(await linkImporter(request, forwarder.token, shipment.id, 999999), 400, INVALID);
    await expectMessage(await linkImporter(request, forwarder.token, shipment.id, companyId(dormantCode)), 400, INVALID);
    await expectMessage(await linkImporter(request, forwarder.token, shipment.id, forwarder.companyId), 400, INVALID);

    const straitsId = companyId('STRAITS_FRESH_DEMO');
    const linked = await linkImporter(request, forwarder.token, shipment.id, straitsId);
    expect(linked.status(), await linked.text()).toBe(200);
    await expectMessage(await request.patch(`/api/shipments/${shipment.id}/link-importer`, {
      headers: bearer(forwarder.token),
    }), 400, MISSING_SHIPMENT);
    const detail = await request.get(`/api/shipments/${shipment.id}`, { headers: bearer(forwarder.token) });
    const detailBody = await detail.text();
    expect(detail.status(), detailBody).toBe(200);
    expect(JSON.parse(detailBody).importer.code).toBe('STRAITS_FRESH_DEMO');
  } finally {
    cleanup([forwarder.email], [dormantCode]);
  }
});

test('re-linking and unlinking move the shipment between importer dashboards', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Shipment linking is covered once on desktop.');
  test.setTimeout(120_000);
  const suffix = randomBytes(3).toString('hex');
  const pacificCode = `CDG124R${suffix}`.slice(0, 50);
  const pacificName = `Pacific Gate ${suffix}`;
  insertCompany(pacificCode, pacificName, true);
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const straits = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const pacific = await registerAccount(request, 'IMPORTER', pacificCode, 'Casey Pacific');
  const first = await createShipment(request, forwarder.token, `Ever Steady ${suffix}`);
  const second = await createShipment(request, forwarder.token, `Feeder North ${suffix}`);
  try {
    const straitsOrg = (await searchImporters(request, forwarder.token, 'Straits Fresh')).items
      .find(item => item.code === 'STRAITS_FRESH_DEMO');
    const pacificOrg = (await searchImporters(request, forwarder.token, pacificName)).items
      .find(item => item.code === pacificCode);
    expect(straitsOrg).toBeTruthy();
    expect(pacificOrg).toBeTruthy();

    expect((await linkImporter(request, forwarder.token, first.id, straitsOrg!.id)).status()).toBe(200);
    expect((await linkImporter(request, forwarder.token, second.id, pacificOrg!.id)).status()).toBe(200);

    await signIn(page, straits.email, '/importer');
    await expect(page.getByRole('link', { name: first.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: second.shipmentReference })).toHaveCount(0);
    await expectHidden(request, straits.token, second);

    await signIn(page, pacific.email, '/importer');
    await expect(page.getByRole('link', { name: second.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: first.shipmentReference })).toHaveCount(0);
    await expectHidden(request, pacific.token, first);

    const moved = await linkImporter(request, forwarder.token, first.id, pacificOrg!.id);
    const movedBody = await moved.text();
    expect(moved.status(), movedBody).toBe(200);
    expect(JSON.parse(movedBody).importer.code).toBe(pacificCode);

    await signIn(page, straits.email, '/importer');
    await expect(page.getByText(EMPTY_LIST)).toBeVisible();
    await expect(page.getByRole('link', { name: first.shipmentReference })).toHaveCount(0);
    await page.goto(`/importer/shipments/${first.id}`);
    await expect(page.getByRole('alert')).toHaveText(NO_ACCESS);
    await expectHidden(request, straits.token, first);

    await signIn(page, pacific.email, '/importer');
    await expect(page.getByRole('link', { name: first.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: second.shipmentReference })).toBeVisible();
    await page.goto(`/importer/shipments/${first.id}`);
    await expect(page.getByRole('heading', { name: first.shipmentReference })).toBeVisible();
    await expect(page.getByText(first.vessel).first()).toBeVisible();

    const cleared = await linkImporter(request, forwarder.token, first.id, null);
    const clearedBody = await cleared.text();
    expect(cleared.status(), clearedBody).toBe(200);
    expect(JSON.parse(clearedBody).importer).toBeNull();

    await page.goto('/importer');
    await expect(page.getByRole('link', { name: first.shipmentReference })).toHaveCount(0);
    await expect(page.getByRole('link', { name: second.shipmentReference })).toBeVisible();
    await expectHidden(request, pacific.token, first);
    await expectHidden(request, straits.token, first);

    await signIn(page, forwarder.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: first.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: second.shipmentReference })).toBeVisible();
  } finally {
    cleanup([forwarder.email, straits.email, pacific.email], [pacificCode]);
  }
});

test('only the freight forwarder for that shipment can link it', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Shipment linking is covered once on desktop.');
  test.setTimeout(120_000);
  const suffix = randomBytes(3).toString('hex');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const importer = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const otherForwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO', 'Dana Ong');
  const seededManager = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Evan Koh');
  setRole(seededManager.email, 'LOGISTICS_MANAGER');
  const manager = await signInApi(request, seededManager.email);
  expect(manager.role).toBe('LOGISTICS_MANAGER');
  const seededAdmin = await registerAccount(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO', 'Fran Admin');
  setRole(seededAdmin.email, 'ADMIN');
  const administrator = await signInApi(request, seededAdmin.email);
  expect(administrator.role).toBe('ADMIN');
  const unassignedSeed = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Glen Unassigned');
  clearCompany(unassignedSeed.email);
  const unassigned = await signInApi(request, unassignedSeed.email);
  expect(unassigned.companyId).toBeNull();
  const shipment = await createShipment(request, forwarder.token, `Ever Steady ${suffix}`);
  const straitsId = companyId('STRAITS_FRESH_DEMO');
  try {
    for (const actor of [importer, otherForwarder, manager, administrator]) {
      await expectMessage(await linkImporter(request, actor.token, shipment.id, straitsId), 403, LINK_FORBIDDEN);
    }
    await expectMessage(await linkImporter(request, unassigned.token, shipment.id, straitsId), 403, VIEW_COMPANY);
    await expectMessage(await linkImporter(request, null, shipment.id, straitsId), 401, SESSION_ENDED);
    await expectMessage(await linkImporter(request, forwarder.token, 999999, straitsId), 404, NOT_FOUND);
    await expectMessage(await linkImporter(request, forwarder.token, 'not-a-number', straitsId), 404, NOT_FOUND);
    await expectMessage(await linkImporter(request, importer.token, 999999, straitsId), 403, LINK_FORBIDDEN);
    const detail = await request.get(`/api/shipments/${shipment.id}`, { headers: bearer(forwarder.token) });
    const detailBody = await detail.text();
    expect(detail.status(), detailBody).toBe(200);
    expect(JSON.parse(detailBody).importer).toBeNull();

    await signIn(page, importer.email, '/importer');
    await expect(page.getByLabel('Link importer')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Register a shipment' })).toHaveCount(0);
    await signIn(page, manager.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page.getByLabel('Link importer')).toHaveCount(0);
    await signIn(page, administrator.email, '/admin');
    await expect(page.getByRole('heading', { name: 'User management' })).toBeVisible();
    await expect(page.getByLabel('Link importer')).toHaveCount(0);
    await signIn(page, unassigned.email, '/freight-forwarder');
    await expect(page.getByText('No company assigned')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveText(new RegExp(VIEW_COMPANY));
    await expectMessage(await request.get('/api/shipments', { headers: bearer(unassigned.token) }), 403, VIEW_COMPANY);
  } finally {
    cleanup([forwarder.email, importer.email, otherForwarder.email, seededManager.email, seededAdmin.email, unassignedSeed.email]);
  }
});
