import { test, expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const host = process.env.DRIFT_E2E_DB_HOST ?? '';
const port = process.env.DRIFT_E2E_DB_PORT ?? '';
const dbUser = process.env.DRIFT_E2E_DB_USER ?? '';
const dbPassword = process.env.DRIFT_E2E_DB_PASSWORD ?? '';
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const guardReport = process.env.DRIFT_E2E_GUARD_REPORT ?? '';
const dbMode = process.env.DRIFT_E2E_DB_MODE ?? '';
const composeProject = process.env.DRIFT_E2E_COMPOSE_PROJECT ?? '';
const systemIdentifier = process.env.DRIFT_E2E_SYSTEM_IDENTIFIER ?? '';
const password = 'Example123';
const removedMessage = 'Shipment removed successfully';
const restoredMessage = 'Shipment restored successfully';
const archiveForbidden = 'You do not have permission to archive this shipment.';
const restoreForbidden = 'You do not have permission to restore this shipment.';
const alreadyActive = 'This shipment is already active.';
const notFound = 'Shipment not found';

function assertRunContext() {
  if (host !== '127.0.0.1' || port !== '5432' || dbUser !== 'drift' || dbPassword !== 'drift') {
    throw new Error('Host, port, database user, and password must be set explicitly.');
  }
  if (dbMode === 'compose' && !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(composeProject)) {
    throw new Error('The archive runner must pass the selected Compose project.');
  }
  if (dbMode === 'local' && composeProject !== '') {
    throw new Error('A local psql connection must not also name a Compose project.');
  }
  if ((dbMode !== 'compose' && dbMode !== 'local') || !/^[0-9]+$/.test(systemIdentifier)) {
    throw new Error('Run this spec through npm run test:e2e:archive.');
  }
  if (!/^drift_cdg83_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:archive.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  const identity = sql('SELECT system_identifier::text FROM pg_control_system();');
  if (current !== database || comment !== ownerToken || identity !== systemIdentifier) {
    throw new Error('This database has no matching ownership record for this run.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  const variableArgs = Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`]);
  const common = ['-U', dbUser, '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', ...variableArgs];
  const env = { ...process.env, PGPASSWORD: dbPassword };
  if (dbMode === 'compose') {
    return execFileSync('docker', ['compose', '-p', composeProject, 'exec', '-T', 'postgres', 'psql', ...common], {
      cwd: root, encoding: 'utf8', input: query, env,
    }).trim();
  }
  if (dbMode === 'local') {
    return execFileSync('psql', ['-h', host, '-p', port, ...common], {
      encoding: 'utf8', input: query, env,
    }).trim();
  }
  throw new Error('Run this spec through npm run test:e2e:archive.');
}

type Account = { email: string; token: string; id: number; role: string; companyId: number; companyCode: string };
type Shipment = { id: number; shipmentReference: string };

function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

function shipmentStatus(id: number) {
  if (!Number.isInteger(id)) throw new Error('Shipment id must be an integer');
  return sql(`SELECT status FROM shipments WHERE id = ${id};`);
}

function companyId(code: string) {
  return Number(sql('SELECT id FROM companies WHERE code = :\'code\';', { code }));
}

function insertCompany(code: string, name: string) {
  sql(`INSERT INTO companies (code, name, active) VALUES (:'code', :'name', TRUE);`, { code, name });
}

async function registerAccount(
  request: APIRequestContext,
  role: 'FREIGHT_FORWARDER' | 'IMPORTER',
  company: string,
  fullName: string,
) {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg83-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName, email, password, invitationToken: invitation },
  });
  expect(registered.status(), await registered.text()).toBe(201);
  return email;
}

function assignRole(email: string, role: 'ADMIN' | 'LOGISTICS_MANAGER') {
  sql(`UPDATE users SET role_id = (SELECT id FROM roles WHERE code = :'role') WHERE email = :'email';`, { email, role });
  const stored = sql(`SELECT roles.code FROM users JOIN roles ON roles.id = users.role_id WHERE users.email = :'email';`, { email });
  expect(stored).toBe(role);
}

async function signInApi(request: APIRequestContext, email: string): Promise<Account> {
  const signedIn = await request.post('/api/login', { data: { email, password } });
  const bodyText = await signedIn.text();
  expect(signedIn.status(), bodyText).toBe(200);
  const body = JSON.parse(bodyText);
  return {
    email,
    token: body.token,
    id: body.id,
    role: body.role,
    companyId: body.company.id,
    companyCode: body.company.code,
  };
}

async function signIn(page: Page, email: string, url: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page).toHaveURL(url);
}

function shipmentData(shipmentReference: string) {
  return {
    shipmentReference,
    origin: 'Rotterdam',
    destination: 'Jakarta',
    transshipmentPort: 'Singapore',
    motherVessel: 'Ever Steady',
    plannedMotherArrivalAt: '2026-10-15T08:00:00+08:00',
    feederVessel: 'Straits Feeder',
    plannedFeederDepartureAt: '2026-10-16T12:00:00+08:00',
  };
}

async function createShipment(request: APIRequestContext, token: string): Promise<Shipment> {
  const shipmentReference = `CDG83-${randomBytes(4).toString('hex')}`;
  const response = await request.post('/api/shipments', { headers: bearer(token), data: shipmentData(shipmentReference) });
  const body = await response.text();
  expect(response.status(), body).toBe(201);
  return { id: JSON.parse(body).id as number, shipmentReference };
}

async function linkShipment(request: APIRequestContext, token: string, shipmentId: number, importerCompanyId: number) {
  const response = await request.patch(`/api/shipments/${shipmentId}/link-importer`, {
    headers: bearer(token),
    data: { importerCompanyId },
  });
  expect(response.status(), await response.text()).toBe(200);
}

async function archive(request: APIRequestContext, token: string, shipmentId: number) {
  return request.patch(`/api/shipments/${shipmentId}/status`, {
    headers: bearer(token),
    data: { status: 'ARCHIVED' },
  });
}

async function restore(request: APIRequestContext, token: string, shipmentId: number) {
  return request.patch(`/api/shipments/${shipmentId}/status`, {
    headers: bearer(token),
    data: { status: 'ACTIVE' },
  });
}

async function applyStatus(page: Page, status: 'ACTIVE' | 'ARCHIVED') {
  await page.getByLabel('Filter by status').selectOption(status);
  await page.getByRole('button', { name: 'Apply filters' }).click();
  await expect(page).toHaveURL(new RegExp(`[?&]status=${status}(?:&|$)`));
}

function shipmentFacts(id: number) {
  if (!Number.isInteger(id)) throw new Error('Shipment id must be an integer');
  return sql(`SELECT shipment_reference || '|' || origin || '|' || destination || '|' || transshipment_port
    || '|' || mother_vessel || '|' || feeder_vessel || '|' || COALESCE(importer_company_id::text, '')
    FROM shipments WHERE id = ${id};`);
}

type ListedShipment = {
  id: number;
  shipmentReference: string;
  companyId: number;
  status: string;
  updatedByName: string | null;
  updatedAt: string | null;
};

async function listByStatus(request: APIRequestContext, token: string, status: 'ACTIVE' | 'ARCHIVED') {
  const response = await request.get(`/api/shipments?status=${status}`, { headers: bearer(token) });
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  return JSON.parse(body) as ListedShipment[];
}

async function expectMessage(response: APIResponse, status: number, message: string) {
  const body = await response.text();
  expect(response.status(), body).toBe(status);
  expect(JSON.parse(body)).toEqual({ message });
}

async function listReferences(request: APIRequestContext, token: string) {
  const response = await request.get('/api/shipments', { headers: bearer(token) });
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  return JSON.parse(body) as Array<{ id: number; shipmentReference: string; companyId: number; status: string }>;
}

function companies(prefix: string) {
  const suffix = randomBytes(4).toString('hex');
  const owner = `${prefix}O${suffix}`.toUpperCase();
  const importer = `${prefix}I${suffix}`.toUpperCase();
  insertCompany(owner, `${prefix} owner ${suffix}`);
  insertCompany(importer, `${prefix} importer ${suffix}`);
  return { owner, importer };
}

test.beforeAll(() => {
  assertRunContext();
  const report = JSON.parse(readFileSync(guardReport, 'utf8'));
  expect(report).toMatchObject({
    phase: 'before-context-refresh',
    validatedBeforeContextRefresh: true,
    flywaySchemaHistoryPresent: false,
    publicTableCount: 0,
    currentDatabase: database,
    serverPort: 5432,
    datasourceUrl: `jdbc:postgresql://127.0.0.1:5432/${database}`,
    flywayUrl: `jdbc:postgresql://127.0.0.1:5432/${database}`,
    datasourceUsername: 'drift',
    flywayUser: 'drift',
    aisProperty: 'app.ais.enabled',
    aisValue: 'false',
    mailHost: '127.0.0.1',
    mailPort: '1025',
  });
  expect(sql("SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history;"))
    .toBe('1,2,3,4,5,6,7,8,13,14,15,16,17,19,20,21,22,23,24,25');
});

test('an owning admin archives from the list and the stored row leaves both active lists', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner, importer } = companies('C83A');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Avery Forwarder');
  const adminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Avery Admin');
  assignRole(adminEmail, 'ADMIN');
  const importerEmail = await registerAccount(request, 'IMPORTER', importer, 'Avery Importer');
  const otherForwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', importer, 'Avery Other');
  const forwarder = await signInApi(request, forwarderEmail);
  const admin = await signInApi(request, adminEmail);
  const importerAccount = await signInApi(request, importerEmail);
  const otherForwarder = await signInApi(request, otherForwarderEmail);
  expect(admin.role).toBe('ADMIN');
  const owned = await createShipment(request, forwarder.token);
  const keeper = await createShipment(request, forwarder.token);
  const foreign = await createShipment(request, otherForwarder.token);
  await linkShipment(request, forwarder.token, owned.id, companyId(importer));
  await linkShipment(request, otherForwarder.token, foreign.id, companyId(owner));

  await signIn(page, admin.email, '/admin');
  await page.getByRole('link', { name: 'Company shipments' }).click();
  await expect(page).toHaveURL('/admin/shipments');
  await expect(page.getByRole('link', { name: 'Profile' })).toHaveAttribute('href', '/profile');
  await expect(page.getByLabel('Filter by shipment reference')).toBeVisible();
  await expect(page.getByLabel('Filter by vessel')).toBeVisible();
  await expect(page.getByLabel('Filter by customer')).toBeVisible();
  await expect(page.getByLabel('Filter by arrival date')).toBeVisible();
  await page.getByLabel('Filter by shipment reference').fill(owned.shipmentReference);
  await page.getByRole('button', { name: 'Apply filters' }).click();
  await expect(page).toHaveURL(`/admin/shipments?reference=${owned.shipmentReference}`);
  await expect(page.getByRole('link', { name: owned.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${owned.shipmentReference}` })).toBeVisible();
  await expect(page.getByRole('link', { name: keeper.shipmentReference })).toHaveCount(0);
  await expect(page.getByRole('link', { name: foreign.shipmentReference })).toHaveCount(0);
  const listed = await listReferences(request, admin.token);
  expect(listed.find(item => item.id === owned.id)?.companyId).toBe(admin.companyId);
  expect(listed.find(item => item.id === foreign.id)?.companyId).toBe(otherForwarder.companyId);
  expect(listed.map(item => item.status)).toEqual(listed.map(() => 'ACTIVE'));

  await page.getByRole('button', { name: `Remove ${owned.shipmentReference}` }).click();
  const dialog = page.getByRole('dialog', { name: 'Remove shipment' });
  await expect(dialog.getByText(`Removing ${owned.shipmentReference} archives it and keeps its record.`)).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText(removedMessage);
  await expect(page).toHaveURL(`/admin/shipments?reference=${owned.shipmentReference}`);
  await expect(page.getByRole('link', { name: owned.shipmentReference })).toHaveCount(0);
  await page.getByRole('group', { name: 'Search and filter shipments' }).getByRole('button', { name: 'Clear filters' }).click();
  await expect(page).toHaveURL('/admin/shipments');
  await expect(page.getByRole('status')).toHaveText(removedMessage);
  await expect(page.getByRole('link', { name: owned.shipmentReference })).toHaveCount(0);
  await expect(page.getByRole('link', { name: keeper.shipmentReference })).toBeVisible();
  await expect(page.getByRole('link', { name: foreign.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${foreign.shipmentReference}` })).toHaveCount(0);
  expect((await listReferences(request, importerAccount.token)).map(item => item.id)).not.toContain(owned.id);
  expect(shipmentStatus(owned.id)).toBe('ARCHIVED');
  const archivedDetail = await request.get(`/api/shipments/${owned.id}`, { headers: bearer(admin.token) });
  const archivedBody = await archivedDetail.text();
  expect(archivedDetail.status(), archivedBody).toBe(200);
  expect(JSON.parse(archivedBody)).toMatchObject({
    companyId: admin.companyId,
    status: 'ARCHIVED',
    risk: { level: 'LOW' },
  });
  expect(sql(`SELECT COUNT(*) FROM shipments WHERE id = ${owned.id};`)).toBe('1');
  expect(shipmentStatus(keeper.id)).toBe('ACTIVE');
  expect(shipmentStatus(foreign.id)).toBe('ACTIVE');
});

test('an owning logistics manager archives from the detail page', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner, importer } = companies('C83L');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Logan Forwarder');
  const managerEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Logan Manager');
  assignRole(managerEmail, 'LOGISTICS_MANAGER');
  const otherForwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', importer, 'Logan Other');
  const forwarder = await signInApi(request, forwarderEmail);
  const manager = await signInApi(request, managerEmail);
  const otherForwarder = await signInApi(request, otherForwarderEmail);
  expect(manager.role).toBe('LOGISTICS_MANAGER');
  const owned = await createShipment(request, forwarder.token);
  const keeper = await createShipment(request, forwarder.token);
  const foreign = await createShipment(request, otherForwarder.token);
  await linkShipment(request, otherForwarder.token, foreign.id, companyId(owner));

  await signIn(page, manager.email, '/logistics-manager');
  await page.goto(`/logistics-manager/shipments/${foreign.id}`);
  await expect(page.getByRole('heading', { name: foreign.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${foreign.shipmentReference}` })).toHaveCount(0);
  await page.goto(`/logistics-manager/shipments/${owned.id}`);
  await expect(page.getByRole('heading', { name: owned.shipmentReference })).toBeVisible();
  await page.getByRole('button', { name: `Remove ${owned.shipmentReference}` }).click();
  await page.getByRole('dialog', { name: 'Remove shipment' }).getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page).toHaveURL('/logistics-manager');
  await expect(page.getByRole('status')).toHaveText(removedMessage);
  await expect(page.getByRole('link', { name: owned.shipmentReference })).toHaveCount(0);
  await expect(page.getByRole('link', { name: keeper.shipmentReference })).toBeVisible();
  expect(shipmentStatus(owned.id)).toBe('ARCHIVED');
  expect(sql(`SELECT COUNT(*) FROM shipments WHERE id = ${owned.id};`)).toBe('1');
  expect(shipmentStatus(foreign.id)).toBe('ACTIVE');
});

test('cancel leaves the shipment active', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(90_000);
  const { owner } = companies('C83C');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Casey Forwarder');
  const adminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Casey Admin');
  assignRole(adminEmail, 'ADMIN');
  const forwarder = await signInApi(request, forwarderEmail);
  const admin = await signInApi(request, adminEmail);
  const shipment = await createShipment(request, forwarder.token);

  await signIn(page, admin.email, '/admin');
  await page.getByRole('link', { name: 'Company shipments' }).click();
  await page.getByRole('button', { name: `Remove ${shipment.shipmentReference}` }).click();
  const dialog = page.getByRole('dialog', { name: 'Remove shipment' });
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  expect(shipmentStatus(shipment.id)).toBe('ACTIVE');
  expect((await listReferences(request, admin.token)).map(item => item.id)).toContain(shipment.id);
});

test('freight forwarder and importer creators cannot archive', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner } = companies('C83P');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Parker Forwarder');
  const importerEmail = await registerAccount(request, 'IMPORTER', owner, 'Parker Importer');
  const forwarder = await signInApi(request, forwarderEmail);
  const importer = await signInApi(request, importerEmail);
  const forwarderShipment = await createShipment(request, forwarder.token);
  const importerShipment = await createShipment(request, importer.token);
  const forwarderBefore = shipmentStatus(forwarderShipment.id);
  const importerBefore = shipmentStatus(importerShipment.id);

  await signIn(page, forwarder.email, '/freight-forwarder');
  await expect(page.getByRole('link', { name: forwarderShipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: /Remove/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, importer.email, '/importer');
  await expect(page.getByRole('link', { name: importerShipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: /Remove/ })).toHaveCount(0);
  await expectMessage(await archive(request, forwarder.token, forwarderShipment.id), 403, archiveForbidden);
  await expectMessage(await archive(request, importer.token, importerShipment.id), 403, archiveForbidden);
  expect(shipmentStatus(forwarderShipment.id)).toBe(forwarderBefore);
  expect(shipmentStatus(importerShipment.id)).toBe(importerBefore);
});

test('another company admin and logistics manager cannot archive a linked shipment', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner, importer } = companies('C83X');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Quinn Forwarder');
  const otherAdminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', importer, 'Quinn Admin');
  const otherManagerEmail = await registerAccount(request, 'FREIGHT_FORWARDER', importer, 'Quinn Manager');
  assignRole(otherAdminEmail, 'ADMIN');
  assignRole(otherManagerEmail, 'LOGISTICS_MANAGER');
  const forwarder = await signInApi(request, forwarderEmail);
  const otherAdmin = await signInApi(request, otherAdminEmail);
  const otherManager = await signInApi(request, otherManagerEmail);
  const shipment = await createShipment(request, forwarder.token);
  await linkShipment(request, forwarder.token, shipment.id, companyId(importer));
  const before = shipmentStatus(shipment.id);

  await signIn(page, otherAdmin.email, '/admin');
  await page.getByRole('link', { name: 'Company shipments' }).click();
  await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${shipment.shipmentReference}` })).toHaveCount(0);
  await page.goto(`/admin/shipments/${shipment.id}`);
  await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${shipment.shipmentReference}` })).toHaveCount(0);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, otherManager.email, '/logistics-manager');
  await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${shipment.shipmentReference}` })).toHaveCount(0);
  await page.goto(`/logistics-manager/shipments/${shipment.id}`);
  await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${shipment.shipmentReference}` })).toHaveCount(0);
  await expectMessage(await archive(request, otherAdmin.token, shipment.id), 404, notFound);
  await expectMessage(await archive(request, otherManager.token, shipment.id), 404, notFound);
  expect(shipmentStatus(shipment.id)).toBe(before);
});

test('an admin sees who archived a shipment and restores it with its details unchanged', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner, importer } = companies('C83R');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Riley Forwarder');
  const adminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Riley Admin');
  assignRole(adminEmail, 'ADMIN');
  const forwarder = await signInApi(request, forwarderEmail);
  const admin = await signInApi(request, adminEmail);
  const owned = await createShipment(request, forwarder.token);
  const keeper = await createShipment(request, forwarder.token);
  await linkShipment(request, forwarder.token, owned.id, companyId(importer));
  const before = shipmentFacts(owned.id);

  await signIn(page, admin.email, '/admin');
  await page.getByRole('link', { name: 'Company shipments' }).click();
  await expect(page.getByRole('columnheader', { name: 'Archived by' })).toHaveCount(0);
  await page.getByRole('button', { name: `Remove ${owned.shipmentReference}` }).click();
  await page.getByRole('dialog', { name: 'Remove shipment' }).getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText(removedMessage);

  await applyStatus(page, 'ARCHIVED');
  await expect(page.getByRole('columnheader', { name: 'Archived by' })).toBeVisible();
  const archived = (await listByStatus(request, admin.token, 'ARCHIVED')).find(item => item.id === owned.id);
  expect(archived?.updatedByName).toBe('Riley Admin');
  expect(archived?.updatedAt).toBeTruthy();
  const archivedBy = page.getByRole('row', { name: owned.shipmentReference }).getByRole('cell', { name: /Riley Admin on / });
  await expect(archivedBy).toBeVisible();
  await expect(archivedBy.locator('time')).toHaveAttribute('datetime', archived?.updatedAt ?? '');
  await expect(page.getByRole('link', { name: keeper.shipmentReference })).toHaveCount(0);

  await page.getByRole('button', { name: `Restore ${owned.shipmentReference}` }).click();
  const dialog = page.getByRole('dialog', { name: 'Restore shipment' });
  await expect(dialog.getByText(`Restoring ${owned.shipmentReference} returns it to your company's active shipments. Its other details stay unchanged.`)).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(dialog).toHaveCount(0);
  expect(shipmentStatus(owned.id)).toBe('ARCHIVED');
  expect(shipmentFacts(owned.id)).toBe(before);

  await page.getByRole('button', { name: `Restore ${owned.shipmentReference}` }).click();
  await page.getByRole('dialog', { name: 'Restore shipment' }).getByRole('button', { name: 'Restore', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText(restoredMessage);
  await expect(page.getByRole('link', { name: owned.shipmentReference })).toHaveCount(0);
  await applyStatus(page, 'ACTIVE');
  await expect(page.getByRole('columnheader', { name: 'Archived by' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: owned.shipmentReference })).toBeVisible();
  await expect(page.getByRole('link', { name: keeper.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${owned.shipmentReference}` })).toBeVisible();
  await expect(page.getByRole('button', { name: `Restore ${owned.shipmentReference}` })).toHaveCount(0);

  expect(shipmentStatus(owned.id)).toBe('ACTIVE');
  expect(shipmentFacts(owned.id)).toBe(before);
  const detail = await request.get(`/api/shipments/${owned.id}`, { headers: bearer(admin.token) });
  const detailBody = await detail.text();
  expect(detail.status(), detailBody).toBe(200);
  const restored = JSON.parse(detailBody);
  expect(restored).toMatchObject({
    companyId: admin.companyId,
    shipmentReference: owned.shipmentReference,
    origin: 'Rotterdam',
    destination: 'Jakarta',
    transshipmentPort: 'Singapore',
    motherVessel: 'Ever Steady',
    feederVessel: 'Straits Feeder',
    status: 'ACTIVE',
    importer: { id: companyId(importer) },
  });
  expect(restored.updatedByName).toBeUndefined();
  expect(restored.updatedAt).toBeUndefined();
  await page.getByRole('link', { name: owned.shipmentReference }).click();
  await expect(page.getByRole('heading', { name: owned.shipmentReference })).toBeVisible();
  await expect(page.getByText('Rotterdam to Jakarta')).toBeVisible();
  await expectMessage(await restore(request, admin.token, owned.id), 409, alreadyActive);
  await expectMessage(await restore(request, admin.token, keeper.id), 409, alreadyActive);
  expect(shipmentFacts(owned.id)).toBe(before);
  expect(shipmentStatus(keeper.id)).toBe('ACTIVE');
});

test('an importer sees only archived shipments linked to their organisation', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner, importer } = companies('C83V');
  const outsider = `C83VX${randomBytes(4).toString('hex')}`.toUpperCase();
  insertCompany(outsider, `Outside ${outsider}`);
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Vera Forwarder');
  const adminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Vera Admin');
  const importerEmail = await registerAccount(request, 'IMPORTER', importer, 'Vera Importer');
  const outsiderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', outsider, 'Vera Outsider');
  const outsiderAdminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', outsider, 'Vera Outside Admin');
  assignRole(adminEmail, 'ADMIN');
  assignRole(outsiderAdminEmail, 'ADMIN');
  const forwarder = await signInApi(request, forwarderEmail);
  const admin = await signInApi(request, adminEmail);
  const importerAccount = await signInApi(request, importerEmail);
  const outsiderForwarder = await signInApi(request, outsiderEmail);
  const outsiderAdmin = await signInApi(request, outsiderAdminEmail);
  const linked = await createShipment(request, forwarder.token);
  const privateShipment = await createShipment(request, forwarder.token);
  const foreign = await createShipment(request, outsiderForwarder.token);
  await linkShipment(request, forwarder.token, linked.id, companyId(importer));
  await expectMessage(await archive(request, admin.token, linked.id), 200, removedMessage);
  await expectMessage(await archive(request, admin.token, privateShipment.id), 200, removedMessage);
  await expectMessage(await archive(request, outsiderAdmin.token, foreign.id), 200, removedMessage);

  const visible = await listByStatus(request, importerAccount.token, 'ARCHIVED');
  expect(visible.map(item => item.id)).toEqual([linked.id]);
  expect(visible[0]).toMatchObject({ updatedByName: 'Vera Admin', status: 'ARCHIVED', companyId: admin.companyId });
  await signIn(page, importerAccount.email, '/importer');
  await applyStatus(page, 'ARCHIVED');
  await expect(page.getByRole('columnheader', { name: 'Archived by' })).toBeVisible();
  await expect(page.getByRole('cell', { name: /Vera Admin on / })).toBeVisible();
  await expect(page.getByRole('link', { name: linked.shipmentReference })).toBeVisible();
  await expect(page.getByRole('link', { name: privateShipment.shipmentReference })).toHaveCount(0);
  await expect(page.getByRole('link', { name: foreign.shipmentReference })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Restore/ })).toHaveCount(0);
});

test('a user without permission does not see restore and the API returns 403', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner, importer } = companies('C83F');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Parker Forwarder');
  const importerEmail = await registerAccount(request, 'IMPORTER', owner, 'Parker Importer');
  const adminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Parker Admin');
  const otherAdminEmail = await registerAccount(request, 'FREIGHT_FORWARDER', importer, 'Parker Other Admin');
  assignRole(adminEmail, 'ADMIN');
  assignRole(otherAdminEmail, 'ADMIN');
  const forwarder = await signInApi(request, forwarderEmail);
  const importerAccount = await signInApi(request, importerEmail);
  const admin = await signInApi(request, adminEmail);
  const otherAdmin = await signInApi(request, otherAdminEmail);
  const shipment = await createShipment(request, forwarder.token);
  await linkShipment(request, forwarder.token, shipment.id, companyId(importer));
  await expectMessage(await archive(request, admin.token, shipment.id), 200, removedMessage);
  const before = shipmentFacts(shipment.id);

  await signIn(page, forwarder.email, '/freight-forwarder');
  await applyStatus(page, 'ARCHIVED');
  await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('cell', { name: /Parker Admin on / })).toBeVisible();
  await expect(page.getByRole('button', { name: /Restore/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, importerAccount.email, '/importer');
  await applyStatus(page, 'ARCHIVED');
  await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: /Restore/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, otherAdmin.email, '/admin');
  await page.getByRole('link', { name: 'Company shipments' }).click();
  await applyStatus(page, 'ARCHIVED');
  await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Restore ${shipment.shipmentReference}` })).toHaveCount(0);
  await page.goto(`/admin/shipments/${shipment.id}`);
  await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Restore ${shipment.shipmentReference}` })).toHaveCount(0);

  await expectMessage(await restore(request, forwarder.token, shipment.id), 403, restoreForbidden);
  await expectMessage(await restore(request, importerAccount.token, shipment.id), 403, restoreForbidden);
  await expectMessage(await restore(request, otherAdmin.token, shipment.id), 404, notFound);
  expect(shipmentStatus(shipment.id)).toBe('ARCHIVED');
  expect(shipmentFacts(shipment.id)).toBe(before);
});

test('a logistics manager restores an archived shipment from its detail page', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  const { owner } = companies('C83D');
  const forwarderEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Logan Forwarder');
  const managerEmail = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Logan Manager');
  assignRole(managerEmail, 'LOGISTICS_MANAGER');
  const forwarder = await signInApi(request, forwarderEmail);
  const manager = await signInApi(request, managerEmail);
  const shipment = await createShipment(request, forwarder.token);
  const before = shipmentFacts(shipment.id);
  await expectMessage(await archive(request, manager.token, shipment.id), 200, removedMessage);

  await signIn(page, manager.email, '/logistics-manager');
  await applyStatus(page, 'ARCHIVED');
  await expect(page.getByRole('cell', { name: /Logan Manager on / })).toBeVisible();
  await page.goto(`/logistics-manager/shipments/${shipment.id}`);
  await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${shipment.shipmentReference}` })).toHaveCount(0);
  await page.getByRole('button', { name: `Restore ${shipment.shipmentReference}` }).click();
  await page.getByRole('dialog', { name: 'Restore shipment' }).getByRole('button', { name: 'Restore', exact: true }).click();
  await expect(page).toHaveURL('/logistics-manager');
  await expect(page.getByRole('status')).toHaveText(restoredMessage);
  await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  await expect(page.getByRole('button', { name: `Remove ${shipment.shipmentReference}` })).toBeVisible();
  expect(shipmentStatus(shipment.id)).toBe('ACTIVE');
  expect(shipmentFacts(shipment.id)).toBe(before);
});
