import { test, expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const ASSIGNED = 'User assigned successfully';
const INVALID = 'Invalid role or organisation selected.';
const ASSIGNMENT_FORBIDDEN = 'Only an administrator can assign users';
const USER_NOT_FOUND = 'User not found.';
const ONE_ADMIN = 'A company can have only one admin.';
const MISSING_ASSIGNMENT = 'Assignment information is missing or invalid';
const SESSION_ENDED = 'Your session has ended. Log in again.';
const NO_ACCESS = 'You do not have access to this shipment';
const NOT_FOUND = 'Shipment not found';
const EMPTY_LIST = 'No shipments yet. Shipments registered for your company will appear here.';
const VIEW_COMPANY = 'Your account must belong to an active company to view shipments';
const CREATE_COMPANY = 'Your account must belong to an active company to create shipments';
const VIEW_ORGANISATIONS = 'Your account must belong to an active company to view organisations';
const ORGANISATION_TYPE = 'Organisation type must be importer';
const LINK_FORBIDDEN = "Only a freight forwarder in the shipment's company can link it to an importer";
const MISSING_SHIPMENT = 'Shipment information is missing or invalid';
const IMPORT_ROLE = 'Your role is not authorized to import shipments in bulk';
const IMPORT_ADMIN = 'ADMIN bulk-import access is deferred until company type is defined';
const IMPORT_COMPANY = 'Bulk import requires an active company';
const IMPORT_JOB = 'Shipment import job not found';
const ROLE_LABELS = ['Administrator', 'Freight forwarder', 'Importer', 'Logistics manager'];

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg112_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:assignment. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the assignment runner. Refusing to write fixtures.');
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

function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function expectMessage(response: APIResponse, status: number, message: string) {
  const body = await response.text();
  expect(response.status(), body).toBe(status);
  const parsed = JSON.parse(body) as { message?: string };
  expect(parsed.message).toBe(message);
  if (status !== 200) expect(parsed).toEqual({ message });
}

async function registerAccount(
  request: APIRequestContext,
  role: 'FREIGHT_FORWARDER' | 'IMPORTER',
  company: string,
  fullName: string,
): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg112-e2e-${randomBytes(8).toString('hex')}@example.com`;
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
  expect(body.role).toEqual(expect.any(String));
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

function promoteToAdmin(email: string) {
  setRole(email, 'ADMIN');
}

function companyId(code: string) {
  return Number(sql('SELECT id FROM companies WHERE code = :\'code\';', { code }));
}

function insertCompany(code: string, name: string, active: boolean) {
  sql(`INSERT INTO companies (code, name, active) VALUES (:'code', :'name', ${active ? 'TRUE' : 'FALSE'});`, { code, name });
}

function setCompanyActive(code: string, active: boolean) {
  sql(`UPDATE companies SET active = ${active ? 'TRUE' : 'FALSE'} WHERE code = :'code';`, { code });
}

type Shipment = { id: number; shipmentReference: string; vessel: string; version: number };

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

async function createShipment(request: APIRequestContext, token: string, vessel = 'Ever Steady'): Promise<Shipment> {
  const shipmentReference = `CDG112-${randomBytes(4).toString('hex')}`;
  const response = await request.post('/api/shipments', {
    headers: bearer(token),
    data: shipmentData(shipmentReference, vessel),
  });
  const body = await response.text();
  expect(response.status(), body).toBe(201);
  const created = JSON.parse(body);
  return { id: created.id as number, shipmentReference, vessel, version: created.version as number };
}

async function assign(request: APIRequestContext, token: string | null, userId: string | number, organisationId: number | null, role: string) {
  return request.patch(`/api/admin/users/${userId}/assign`, {
    ...(token ? { headers: bearer(token) } : {}),
    data: { organisationId, role },
  });
}

function csvFor(reference: string) {
  return Buffer.from(
    'Tracking/BL No.,Origin Port,Destination Port,Transshipment Port,Mother Vessel,Planned Mother Arrival,Feeder Vessel,Planned Feeder Departure,Importer Organisation\n'
    + `${reference},Port of Singapore,Port of Rotterdam,Port of Tanjung Pelepas,Example Mother Vessel,2026-10-10T09:30:00+08:00,Example Feeder Vessel,2026-10-10T15:45:00+08:00,\n`,
  );
}

async function uploadCsv(request: APIRequestContext, token: string, reference: string) {
  return request.post('/api/shipments/import', {
    headers: bearer(token),
    multipart: { file: { name: 'shipments.csv', mimeType: 'text/csv', buffer: csvFor(reference) } },
  });
}

function cleanup(emails: string[], companies: string[] = []) {
  for (const email of emails) {
    sql(`DELETE FROM shipment_import_jobs WHERE requested_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
  for (const code of companies) {
    sql(`DELETE FROM companies WHERE code = :'code'
      AND NOT EXISTS (SELECT 1 FROM users WHERE company_id = companies.id)
      AND NOT EXISTS (SELECT 1 FROM invitations WHERE company_id = companies.id)
      AND NOT EXISTS (SELECT 1 FROM shipments WHERE company_id = companies.id OR importer_company_id = companies.id)
      AND NOT EXISTS (SELECT 1 FROM shipment_import_jobs WHERE managing_company_id = companies.id);`, { code });
  }
}

async function signIn(page: Page, email: string, url: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(url);
}

async function expectHiddenShipment(request: APIRequestContext, token: string, shipment: Shipment) {
  const list = await request.get('/api/shipments', { headers: bearer(token) });
  const listBody = await list.text();
  expect(list.status(), listBody).toBe(200);
  expect(listBody).not.toContain(shipment.shipmentReference);
  expect(listBody).not.toContain(shipment.vessel);
  await expectMessage(await request.get(`/api/shipments/${shipment.id}`, { headers: bearer(token) }), 403, NO_ACCESS);
  await expectMessage(await request.get(`/api/shipments/${shipment.id}/tracking`, { headers: bearer(token) }), 404, NOT_FOUND);
  await expectMessage(await request.put(`/api/shipments/${shipment.id}`, {
    headers: bearer(token),
    data: shipmentData(shipment.shipmentReference, shipment.vessel, shipment.version),
  }), 404, NOT_FOUND);
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('an administrator assigns a user and the new organisation and role take effect at the next login', { tag: '@disposable-database' }, async ({ page, browser, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(90_000);
  const member = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const shipment = await createShipment(request, member.token, 'Ever Steady Alice');
  const administrator = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Casey Admin');
  promoteToAdmin(administrator.email);
  const adminSession = await signInApi(request, administrator.email);
  expect(adminSession.role).toBe('ADMIN');
  expect(adminSession.companyCode).toBe('HARBOURLINE_DEMO');
  const adminPage = await browser.newPage();
  try {
    await signIn(page, member.email, '/freight-forwarder');
    await expect(page.getByRole('heading', { name: 'Shipment portfolio' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Register a shipment' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'User management' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();

    await signIn(adminPage, administrator.email, '/admin');
    await expect(adminPage.getByRole('heading', { name: 'User management' })).toBeVisible();
    await expect(adminPage.getByRole('link', { name: 'User management' })).toHaveAttribute('aria-current', 'page');
    const choice = adminPage.getByRole('radio', { name: /Alice Tan/ });
    await choice.check();
    await adminPage.locator('#assignment-organisation').selectOption({ label: 'Straits Fresh Imports (Demo)' });
    await adminPage.locator('#assignment-role').selectOption({ label: 'Importer' });
    await adminPage.getByRole('button', { name: /Save assignment/ }).click();
    await expect(adminPage.getByRole('status')).toHaveText(ASSIGNED);
    await expect(choice).toHaveAccessibleName(/Importer/);
    await expect(choice).toHaveAccessibleName(/Straits Fresh Imports \(Demo\)/);

    const adminStillSignedIn = await request.get('/api/session', { headers: bearer(adminSession.token) });
    expect(adminStillSignedIn.status()).toBe(200);
    await expectMessage(await request.get('/api/session', { headers: bearer(member.token) }), 401, SESSION_ENDED);

    await page.reload();
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('alert')).toHaveText(SESSION_ENDED);

    await signIn(page, member.email, '/importer');
    await expect(page.getByRole('heading', { name: 'Shipment overview' })).toBeVisible();
    await expect(page.locator('.company-card strong')).toHaveText('Straits Fresh Imports (Demo)');
    await expect(page.getByRole('heading', { name: 'Register a shipment' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'User management' })).toHaveCount(0);
    await expect(page.getByText(EMPTY_LIST)).toBeVisible();
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toHaveCount(0);
    await page.goto(`/importer/shipments/${shipment.id}`);
    await expect(page.getByRole('alert')).toHaveText(NO_ACCESS);

    const refreshed = await signInApi(request, member.email);
    expect(refreshed.role).toBe('IMPORTER');
    expect(refreshed.companyCode).toBe('STRAITS_FRESH_DEMO');
    await expectHiddenShipment(request, refreshed.token, shipment);
  } finally {
    await adminPage.close();
    cleanup([member.email, administrator.email]);
  }
});

test('an invalid role or inactive organisation is rejected and the current assignment stays in place', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const archivedCode = `CDG112A${suffix}`.slice(0, 50);
  const northwindCode = `CDG112N${suffix}`.slice(0, 50);
  const archivedName = `Archived Cold Store ${suffix}`;
  const northwindName = `Northwind Cold Store ${suffix}`;
  insertCompany(archivedCode, archivedName, false);
  insertCompany(northwindCode, northwindName, true);
  const northwindId = companyId(northwindCode);
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Pat Lim ${suffix}`);
  const administrator = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  promoteToAdmin(administrator.email);
  const adminSession = await signInApi(request, administrator.email);
  try {
    await expectMessage(await assign(request, adminSession.token, target.id, northwindId, 'PLANNER'), 400, INVALID);
    await expectMessage(await assign(request, adminSession.token, target.id, 999999, 'IMPORTER'), 400, INVALID);
    await expectMessage(await assign(request, adminSession.token, target.id, companyId(archivedCode), 'IMPORTER'), 400, INVALID);
    await expectMessage(await assign(request, adminSession.token, target.id, null, 'IMPORTER'), 400, INVALID);
    const unchanged = await request.get('/api/session', { headers: bearer(target.token) });
    const unchangedBody = await unchanged.text();
    expect(unchanged.status(), unchangedBody).toBe(200);
    expect(JSON.parse(unchangedBody).role).toBe('FREIGHT_FORWARDER');
    expect(JSON.parse(unchangedBody).company.code).toBe('HARBOURLINE_DEMO');

    const organisations = await request.get('/api/admin/organisations', { headers: bearer(adminSession.token) });
    const organisationBody = await organisations.text();
    expect(organisations.status(), organisationBody).toBe(200);
    const organisationNames = (JSON.parse(organisationBody) as { name: string }[]).map(item => item.name);
    expect(organisationNames).toContain('Harbourline Logistics (Demo)');
    expect(organisationNames).toContain(northwindName);
    expect(organisationNames).not.toContain(archivedName);
    const roles = await request.get('/api/admin/roles', { headers: bearer(adminSession.token) });
    const roleBody = await roles.text();
    expect(roles.status(), roleBody).toBe(200);
    expect((JSON.parse(roleBody) as { label: string }[]).map(item => item.label).sort()).toEqual([...ROLE_LABELS].sort());

    await signIn(page, administrator.email, '/admin');
    await page.getByRole('radio', { name: new RegExp(`Pat Lim ${suffix}`) }).check();
    await expect(page.locator('#assignment-role')).toBeEnabled();
    const roleLabels = await page.locator('#assignment-role option').allTextContents();
    for (const label of ROLE_LABELS) expect(roleLabels).toContain(label);
    expect(roleLabels.join('\n')).not.toMatch(/Planner/);
    const organisationLabels = await page.locator('#assignment-organisation option').allTextContents();
    expect(organisationLabels).toContain(northwindName);
    expect(organisationLabels).not.toContain(archivedName);
    await page.locator('#assignment-organisation').selectOption({ label: northwindName });
    await page.locator('#assignment-role').selectOption({ label: 'Importer' });
    setCompanyActive(northwindCode, false);
    await page.getByRole('button', { name: /Save assignment/ }).click();
    await expect(page.getByRole('alert')).toHaveText(INVALID);
    await expect(page.getByRole('radio', { name: new RegExp(`Pat Lim ${suffix}`) })).toHaveAccessibleName(/Freight forwarder/);
    await expect(page.getByRole('radio', { name: new RegExp(`Pat Lim ${suffix}`) })).toHaveAccessibleName(/Harbourline Logistics \(Demo\)/);
    const stillForwarder = await signInApi(request, target.email);
    expect(stillForwarder.role).toBe('FREIGHT_FORWARDER');
    expect(stillForwarder.companyCode).toBe('HARBOURLINE_DEMO');
  } finally {
    cleanup([target.email, administrator.email], [archivedCode, northwindCode]);
  }
});

test('someone who is not an administrator cannot assign users or open user management', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(120_000);
  const importer = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Dana Ong');
  const seededManager = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Evan Koh');
  setRole(seededManager.email, 'LOGISTICS_MANAGER');
  const manager = await signInApi(request, seededManager.email);
  expect(manager.role).toBe('LOGISTICS_MANAGER');
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  try {
    for (const actor of [importer, forwarder, manager]) {
      await expectMessage(await assign(request, actor.token, target.id, importer.companyId, 'IMPORTER'), 403, ASSIGNMENT_FORBIDDEN);
      await expectMessage(await request.get('/api/admin/users', { headers: bearer(actor.token) }), 403, ASSIGNMENT_FORBIDDEN);
      await expectMessage(await request.get('/api/admin/roles', { headers: bearer(actor.token) }), 403, ASSIGNMENT_FORBIDDEN);
      await expectMessage(await request.get('/api/admin/organisations', { headers: bearer(actor.token) }), 403, ASSIGNMENT_FORBIDDEN);
    }
    await expectMessage(await assign(request, null, target.id, importer.companyId, 'IMPORTER'), 401, SESSION_ENDED);
    await expectMessage(await request.get('/api/admin/users'), 401, SESSION_ENDED);
    await expectMessage(await request.patch(`/api/admin/users/${target.id}/assign`, { headers: bearer(importer.token) }), 400, MISSING_ASSIGNMENT);

    await signIn(page, importer.email, '/importer');
    await expect(page.getByRole('heading', { name: 'Shipment overview' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Register a shipment' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'User management' })).toHaveCount(0);
    await page.goto('/admin');
    await expect(page).toHaveURL('/importer');
    await expect(page.getByRole('heading', { name: 'User management' })).toHaveCount(0);

    await signIn(page, manager.email, '/freight-forwarder');
    await expect(page.getByRole('heading', { name: 'Shipment portfolio' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Register a shipment' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'User management' })).toHaveCount(0);
    await page.goto('/admin');
    await expect(page).toHaveURL('/freight-forwarder');
    await expect(page.getByRole('heading', { name: 'User management' })).toHaveCount(0);

    await signIn(page, forwarder.email, '/freight-forwarder');
    await page.goto('/admin');
    await expect(page).toHaveURL('/freight-forwarder');
    await expect(page.getByRole('heading', { name: 'User management' })).toHaveCount(0);
    const stillForwarder = await signInApi(request, target.email);
    expect(stillForwarder.role).toBe('FREIGHT_FORWARDER');
    expect(stillForwarder.companyCode).toBe('HARBOURLINE_DEMO');
  } finally {
    cleanup([importer.email, forwarder.email, seededManager.email, target.email]);
  }
});

test('a real assignment ends the session and an unchanged assignment leaves it in place', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(90_000);
  const administrator = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Casey Admin');
  promoteToAdmin(administrator.email);
  const adminSession = await signInApi(request, administrator.email);
  const same = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Same Assignment');
  const roleOnly = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Role Only');
  const roleShipment = await createShipment(request, roleOnly.token, 'Ever Steady Role');
  const organisationOnly = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Organisation Only');
  const organisationShipment = await createShipment(request, organisationOnly.token, 'Ever Steady Organisation');
  try {
    await expectMessage(await assign(request, adminSession.token, same.id, same.companyId, 'IMPORTER'), 200, ASSIGNED);
    const kept = await request.get('/api/session', { headers: bearer(same.token) });
    const keptBody = await kept.text();
    expect(kept.status(), keptBody).toBe(200);
    expect(JSON.parse(keptBody).role).toBe('IMPORTER');
    expect(JSON.parse(keptBody).company.code).toBe('STRAITS_FRESH_DEMO');

    await expectMessage(await assign(request, adminSession.token, roleOnly.id, roleOnly.companyId, 'LOGISTICS_MANAGER'), 200, ASSIGNED);
    await expectMessage(await request.get('/api/session', { headers: bearer(roleOnly.token) }), 401, SESSION_ENDED);
    const manager = await signInApi(request, roleOnly.email);
    expect(manager.role).toBe('LOGISTICS_MANAGER');
    expect(manager.companyCode).toBe('HARBOURLINE_DEMO');
    await signIn(page, roleOnly.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: roleShipment.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: 'User management' })).toHaveCount(0);
    const created = await createShipment(request, manager.token, 'Ever Steady Manager');
    const ownList = await request.get('/api/shipments', { headers: bearer(manager.token) });
    const ownListBody = await ownList.text();
    expect(ownList.status(), ownListBody).toBe(200);
    expect(ownListBody).toContain(created.shipmentReference);
    expect(ownListBody).toContain(roleShipment.shipmentReference);
    await expectMessage(await uploadCsv(request, manager.token, `CDG112-LM-${randomBytes(3).toString('hex')}`), 403, IMPORT_ROLE);
    await expectMessage(await request.patch(`/api/shipments/${roleShipment.id}/link-importer`, {
      headers: bearer(manager.token),
      data: { importerCompanyId: companyId('STRAITS_FRESH_DEMO') },
    }), 403, LINK_FORBIDDEN);

    await expectMessage(await assign(request, adminSession.token, organisationOnly.id, companyId('STRAITS_FRESH_DEMO'), 'FREIGHT_FORWARDER'), 200, ASSIGNED);
    await expectMessage(await request.get('/api/session', { headers: bearer(organisationOnly.token) }), 401, SESSION_ENDED);
    const moved = await signInApi(request, organisationOnly.email);
    expect(moved.role).toBe('FREIGHT_FORWARDER');
    expect(moved.companyCode).toBe('STRAITS_FRESH_DEMO');
    await expectHiddenShipment(request, moved.token, organisationShipment);
    await signIn(page, organisationOnly.email, '/freight-forwarder');
    await expect(page.locator('.company-card strong')).toHaveText('Straits Fresh Imports (Demo)');
    await expect(page.getByRole('link', { name: organisationShipment.shipmentReference })).toHaveCount(0);
  } finally {
    cleanup([administrator.email, same.email, roleOnly.email, organisationOnly.email]);
  }
});

test('an unknown user is not found and a company cannot receive a second administrator', { tag: '@disposable-database' }, async ({ request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(90_000);
  const administrator = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Casey Admin');
  promoteToAdmin(administrator.email);
  const adminSession = await signInApi(request, administrator.email);
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Second Admin');
  const straitsId = companyId('STRAITS_FRESH_DEMO');
  try {
    await expectMessage(await assign(request, adminSession.token, 999999, straitsId, 'IMPORTER'), 404, USER_NOT_FOUND);
    await expectMessage(await assign(request, adminSession.token, 'not-a-number', straitsId, 'IMPORTER'), 404, USER_NOT_FOUND);
    await expectMessage(await assign(request, adminSession.token, target.id, administrator.companyId, 'ADMIN'), 409, ONE_ADMIN);
    const stillForwarder = await signInApi(request, target.email);
    expect(stillForwarder.role).toBe('FREIGHT_FORWARDER');
    expect(stillForwarder.companyCode).toBe('HARBOURLINE_DEMO');
    const session = await request.get('/api/session', { headers: bearer(target.token) });
    expect(session.status()).toBe(200);
  } finally {
    cleanup([administrator.email, target.email]);
  }
});

test('a user only sees shipments and functions authorised for their organisation and role', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(120_000);
  const suffix = randomBytes(3).toString('hex');
  const pacificCode = `CDG112P${suffix}`.slice(0, 50);
  insertCompany(pacificCode, `Pacific Gate ${suffix}`, true);
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const importer = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const outsider = await registerAccount(request, 'FREIGHT_FORWARDER', pacificCode, 'Casey Pacific');
  const seededManager = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Evan Koh');
  setRole(seededManager.email, 'LOGISTICS_MANAGER');
  const manager = await signInApi(request, seededManager.email);
  const administrator = await registerAccount(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO', 'Fran Admin');
  promoteToAdmin(administrator.email);
  const adminSession = await signInApi(request, administrator.email);
  const unassignedSeed = await registerAccount(request, 'FREIGHT_FORWARDER', pacificCode, 'Dana Unassigned');
  clearCompany(unassignedSeed.email);
  const unassigned = await signInApi(request, unassignedSeed.email);
  expect(unassigned.companyId).toBeNull();
  const shipment = await createShipment(request, owner.token, `Ever Steady ${suffix}`);
  try {
    await expectHiddenShipment(request, importer.token, shipment);
    await expectHiddenShipment(request, outsider.token, shipment);

    await signIn(page, importer.email, '/importer');
    await expect(page.getByText(EMPTY_LIST)).toBeVisible();
    await expect(page.getByText(shipment.vessel)).toHaveCount(0);
    await page.goto(`/importer/shipments/${shipment.id}`);
    await expect(page.getByRole('alert')).toHaveText(NO_ACCESS);
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toHaveCount(0);

    const straitsShipment = await createShipment(request, importer.token, `Straits Vessel ${suffix}`);
    await expectHiddenShipment(request, manager.token, straitsShipment);
    const ownerList = await request.get('/api/shipments', { headers: bearer(owner.token) });
    const ownerListBody = await ownerList.text();
    expect(ownerList.status(), ownerListBody).toBe(200);
    expect(ownerListBody).toContain(shipment.shipmentReference);
    expect(ownerListBody).toContain(shipment.vessel);
    expect(ownerListBody).not.toContain(straitsShipment.shipmentReference);

    await signIn(page, owner.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page.getByText(shipment.vessel).first()).toBeVisible();
    await expect(page.getByText('No live AIS position').first()).toBeVisible();

    await expectMessage(await request.patch(`/api/shipments/${shipment.id}/link-importer`, {
      headers: bearer(importer.token),
      data: { importerCompanyId: importer.companyId },
    }), 403, LINK_FORBIDDEN);
    await expectMessage(await request.patch(`/api/shipments/999999/link-importer`, {
      headers: bearer(manager.token),
      data: { importerCompanyId: importer.companyId },
    }), 403, LINK_FORBIDDEN);
    await expectMessage(await request.patch(`/api/shipments/${shipment.id}/link-importer`, {
      headers: bearer(outsider.token),
      data: { importerCompanyId: importer.companyId },
    }), 403, LINK_FORBIDDEN);
    await expectMessage(await request.patch(`/api/shipments/${shipment.id}/link-importer`, {
      headers: bearer(owner.token),
    }), 400, MISSING_SHIPMENT);
    const stillUnlinked = await request.get(`/api/shipments/${shipment.id}`, { headers: bearer(owner.token) });
    const stillUnlinkedBody = await stillUnlinked.text();
    expect(stillUnlinked.status(), stillUnlinkedBody).toBe(200);
    expect(JSON.parse(stillUnlinkedBody).importer).toBeNull();

    const linked = await request.patch(`/api/shipments/${shipment.id}/link-importer`, {
      headers: bearer(owner.token),
      data: { importerCompanyId: importer.companyId },
    });
    const linkedBody = await linked.text();
    expect(linked.status(), linkedBody).toBe(200);
    expect(JSON.parse(linkedBody).importer.code).toBe('STRAITS_FRESH_DEMO');
    const linkedVersion = JSON.parse(linkedBody).version as number;

    const importerList = await request.get('/api/shipments', { headers: bearer(importer.token) });
    const importerListBody = await importerList.text();
    expect(importerList.status(), importerListBody).toBe(200);
    expect(importerListBody).toContain(shipment.shipmentReference);
    expect(importerListBody).toContain(shipment.vessel);
    const importerDetail = await request.get(`/api/shipments/${shipment.id}`, { headers: bearer(importer.token) });
    expect(importerDetail.status()).toBe(200);
    const tracking = await request.get(`/api/shipments/${shipment.id}/tracking`, { headers: bearer(importer.token) });
    const trackingBody = await tracking.text();
    expect(tracking.status(), trackingBody).toBe(200);
    const trackingJson = JSON.parse(trackingBody) as { motherVesselName: string; motherVessel: unknown };
    expect(trackingJson.motherVesselName).toBe(shipment.vessel);
    expect(trackingJson.motherVessel).toBeNull();
    await expectMessage(await request.put(`/api/shipments/${shipment.id}`, {
      headers: bearer(importer.token),
      data: shipmentData(shipment.shipmentReference, shipment.vessel, linkedVersion),
    }), 403, NO_ACCESS);
    await expectHiddenShipment(request, outsider.token, shipment);

    await signIn(page, importer.email, '/importer');
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page.getByRole('link', { name: straitsShipment.shipmentReference })).toBeVisible();
    await page.goto(`/importer/shipments/${shipment.id}`);
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page.getByText(shipment.vessel).first()).toBeVisible();
    await expect(page.getByText('No live AIS position').first()).toBeVisible();

    const uploaded = await uploadCsv(request, owner.token, `CDG112-IMP-${suffix}`);
    const uploadedBody = await uploaded.text();
    expect(uploaded.status(), uploadedBody).toBe(202);
    const jobId = JSON.parse(uploadedBody).id as number;
    await expectMessage(await request.get(`/api/shipments/import/${jobId}`, { headers: bearer(importer.token) }), 404, IMPORT_JOB);
    await expectMessage(await request.get(`/api/shipments/import/${jobId}/errors`, { headers: bearer(outsider.token) }), 404, IMPORT_JOB);
    const ownJob = await request.get(`/api/shipments/import/${jobId}`, { headers: bearer(owner.token) });
    expect(ownJob.status()).toBe(200);
    await expectMessage(await uploadCsv(request, importer.token, `CDG112-IMP2-${suffix}`), 403, IMPORT_ROLE);
    await expectMessage(await uploadCsv(request, manager.token, `CDG112-LM-${suffix}`), 403, IMPORT_ROLE);
    await expectMessage(await uploadCsv(request, adminSession.token, `CDG112-AD-${suffix}`), 403, IMPORT_ADMIN);
    await expectMessage(await uploadCsv(request, unassigned.token, `CDG112-NA-${suffix}`), 403, IMPORT_COMPANY);

    await expectMessage(await request.get('/api/shipments', { headers: bearer(unassigned.token) }), 403, VIEW_COMPANY);
    await expectMessage(await request.post('/api/shipments', {
      headers: bearer(unassigned.token),
      data: shipmentData(`CDG112-NA-${suffix}`, 'No Company Vessel'),
    }), 403, CREATE_COMPANY);
    await expectMessage(await request.get('/api/organisations?type=importer&page=0&size=20', { headers: bearer(unassigned.token) }), 403, VIEW_ORGANISATIONS);
    await expectMessage(await request.get('/api/organisations?type=planner', { headers: bearer(owner.token) }), 400, ORGANISATION_TYPE);
    const search = await request.get('/api/organisations?type=importer&name=Straits&page=0&size=20', { headers: bearer(owner.token) });
    const searchBody = await search.text();
    expect(search.status(), searchBody).toBe(200);
    const searchJson = JSON.parse(searchBody) as { items: { code: string }[] };
    expect(searchJson.items.map(item => item.code)).toContain('STRAITS_FRESH_DEMO');
    expect(searchJson.items.map(item => item.code)).not.toContain('HARBOURLINE_DEMO');
    const importerSearch = await request.get('/api/organisations?type=importer&name=Straits&page=0&size=20', { headers: bearer(importer.token) });
    const importerSearchBody = await importerSearch.text();
    expect(importerSearch.status(), importerSearchBody).toBe(200);
    expect((JSON.parse(importerSearchBody) as { items: { code: string }[] }).items.map(item => item.code)).not.toContain('STRAITS_FRESH_DEMO');

    await signIn(page, unassigned.email, '/freight-forwarder');
    await expect(page.getByText('No company assigned')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveText(new RegExp(VIEW_COMPANY));
  } finally {
    cleanup([
      owner.email, importer.email, outsider.email, seededManager.email, administrator.email, unassignedSeed.email,
    ], [pacificCode]);
  }
});
