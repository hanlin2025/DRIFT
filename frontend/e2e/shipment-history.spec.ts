import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
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
const noChanges = 'No changes yet';
const earlierChanges = 'Changes made before history was recorded are not shown.';
const noAccess = 'You do not have access to this shipment';
const installedVersions = '1,2,3,4,5,6,7,8,13,14,15,16,17,19,20,21,22,23,24,25,26,27';

function assertRunContext() {
  if (host !== '127.0.0.1' || port !== '5432' || dbUser !== 'drift' || dbPassword !== 'drift') {
    throw new Error('Host, port, database user, and password must be set explicitly.');
  }
  if (dbMode === 'compose' && !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(composeProject)) {
    throw new Error('The history runner must pass the selected Compose project.');
  }
  if (dbMode === 'local' && composeProject !== '') {
    throw new Error('A local psql connection must not also name a Compose project.');
  }
  if ((dbMode !== 'compose' && dbMode !== 'local') || !/^[0-9]+$/.test(systemIdentifier)) {
    throw new Error('Run this spec through npm run test:e2e:history.');
  }
  if (!/^drift_cdg185_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:history.');
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
  const common = ['-U', dbUser, '-d', database, '-v', 'ON_ERROR_STOP=1', '-Atq', ...variableArgs];
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
  throw new Error('Run this spec through npm run test:e2e:history.');
}

type Account = { email: string; token: string; id: number; fullName: string; companyId: number };

function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

function insertCompany(code: string, name: string) {
  sql(`INSERT INTO companies (code, name, active) VALUES (:'code', :'name', TRUE);`, { code, name });
}

function companyId(code: string) {
  return Number(sql('SELECT id FROM companies WHERE code = :\'code\';', { code }));
}

function companies(prefix: string) {
  const suffix = randomBytes(4).toString('hex');
  const owner = `${prefix}O${suffix}`.toUpperCase();
  const importer = `${prefix}I${suffix}`.toUpperCase();
  const outsider = `${prefix}X${suffix}`.toUpperCase();
  const importerName = `${prefix} importer ${suffix}`;
  insertCompany(owner, `${prefix} owner ${suffix}`);
  insertCompany(importer, importerName);
  insertCompany(outsider, `${prefix} outsider ${suffix}`);
  return { owner, importer, outsider, importerName };
}

async function registerAccount(
  request: APIRequestContext,
  role: 'FREIGHT_FORWARDER' | 'IMPORTER',
  company: string,
  fullName: string,
): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg185-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName, email, password, invitationToken: invitation },
  });
  expect(registered.status(), await registered.text()).toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  const bodyText = await signedIn.text();
  expect(signedIn.status(), bodyText).toBe(200);
  const body = JSON.parse(bodyText);
  return { email, token: body.token, id: body.id, fullName, companyId: body.company.id };
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

async function createShipment(request: APIRequestContext, token: string) {
  const shipmentReference = `CDG185-${randomBytes(4).toString('hex')}`;
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

async function signIn(page: Page, email: string, url: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page).toHaveURL(url);
}

function historySection(page: Page) {
  return page.getByRole('region', { name: 'Change history' });
}

async function openDetail(page: Page, rolePath: string, shipmentId: number, reference: string) {
  await page.goto(`${rolePath}/shipments/${shipmentId}`);
  await expect(page.getByRole('heading', { name: reference })).toBeVisible();
  await expect(historySection(page).getByRole('heading', { name: 'Change history' })).toBeVisible();
}

function insertUnrecordedShipment(account: Account, reference: string) {
  const id = sql(`INSERT INTO shipments (
      company_id, created_by_user_id, updated_by_user_id, shipment_reference,
      origin, destination, transshipment_port, mother_vessel, planned_mother_arrival_at,
      feeder_vessel, planned_feeder_departure_at, status, created_at, updated_at, version)
    VALUES (
      ${account.companyId}, ${account.id}, ${account.id}, :'reference',
      'Rotterdam', 'Jakarta', 'Singapore', 'Ever Steady', TIMESTAMPTZ '2026-10-15 08:00:00+08',
      'Straits Feeder', TIMESTAMPTZ '2026-10-16 12:00:00+08', 'ACTIVE',
      TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-02-01 00:00:00+00', 0)
    RETURNING id;`, { reference });
  sql(`INSERT INTO shipment_change_events (
      shipment_id, actor_user_id, change_type, earlier_changes_unrecorded, recorded_at)
    VALUES (
      ${id}, ${account.id}, 'CREATED', TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00');`);
  return Number(id);
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
    .toBe(installedVersions);
});

test.beforeEach(() => {
  test.setTimeout(90_000);
});

test('editing a shipment adds a history entry for the changed fields', { tag: '@disposable-database' }, async ({ page, request }) => {
  const { owner } = companies('C185E');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Avery Forwarder');
  const shipment = await createShipment(request, forwarder.token);

  await signIn(page, forwarder.email, '/freight-forwarder');
  await openDetail(page, '/freight-forwarder', shipment.id, shipment.shipmentReference);
  await page.getByRole('button', { name: 'Edit shipment' }).click();
  await page.getByLabel('Origin').fill('Hamburg');
  await page.getByRole('button', { name: 'Save shipment' }).click();

  const history = historySection(page);
  const updated = history.getByRole('listitem').filter({ hasText: 'Updated' });
  await expect(updated).toBeVisible();
  await expect(updated).toContainText('by Avery Forwarder');
  await expect(updated.getByText('Origin', { exact: true })).toBeVisible();
  await expect(updated.getByText('Rotterdam')).toBeVisible();
  await expect(updated.getByText('Hamburg')).toBeVisible();
  await expect(history.getByText(noChanges)).toHaveCount(0);
});

test('a new shipment shows that there are no changes yet', { tag: '@disposable-database' }, async ({ page, request }) => {
  const { owner } = companies('C185N');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Nora Forwarder');
  const shipment = await createShipment(request, forwarder.token);

  await signIn(page, forwarder.email, '/freight-forwarder');
  await openDetail(page, '/freight-forwarder', shipment.id, shipment.shipmentReference);

  const history = historySection(page);
  await expect(history.getByText(noChanges)).toBeVisible();
  await expect(history.getByRole('listitem').filter({ hasText: 'Created' })).toContainText('by Nora Forwarder');
  await expect(history.getByText(earlierChanges)).toHaveCount(0);
});

test('a linked importer can view the change history', { tag: '@disposable-database' }, async ({ page, request }) => {
  const { owner, importer, importerName } = companies('C185L');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Lina Forwarder');
  const importerAccount = await registerAccount(request, 'IMPORTER', importer, 'Lina Importer');
  const shipment = await createShipment(request, forwarder.token);
  await linkShipment(request, forwarder.token, shipment.id, companyId(importer));

  await signIn(page, importerAccount.email, '/importer');
  await openDetail(page, '/importer', shipment.id, shipment.shipmentReference);

  const history = historySection(page);
  const linked = history.getByRole('listitem').filter({ hasText: 'Importer linked' });
  await expect(linked).toContainText('by Lina Forwarder');
  await expect(linked.getByText('Importer', { exact: true })).toBeVisible();
  await expect(linked.getByText(importerName)).toBeVisible();
  await expect(history.getByRole('listitem').filter({ hasText: 'Created' })).toContainText('by Lina Forwarder');
  await expect(history.getByText(noChanges)).toHaveCount(0);
});

test('another company is forbidden from the change history', { tag: '@disposable-database' }, async ({ page, request }) => {
  const { owner, outsider } = companies('C185F');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Owen Forwarder');
  const stranger = await registerAccount(request, 'FREIGHT_FORWARDER', outsider, 'Owen Stranger');
  const shipment = await createShipment(request, forwarder.token);

  const history = await request.get(`/api/shipments/${shipment.id}/history`, { headers: bearer(stranger.token) });
  const body = await history.text();
  expect(history.status(), body).toBe(403);
  expect(JSON.parse(body)).toEqual({ message: noAccess });
  expect(history.headers()['cache-control']).toContain('no-store');

  await signIn(page, stranger.email, '/freight-forwarder');
  await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
  await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText(noAccess);
  await expect(page.getByRole('region', { name: 'Change history' })).toHaveCount(0);
  await expect(page.getByText(shipment.shipmentReference)).toHaveCount(0);
});

test('a shipment that predates history shows that earlier changes were not recorded', { tag: '@disposable-database' }, async ({ page, request }) => {
  const { owner } = companies('C185P');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', owner, 'Priya Forwarder');
  const reference = `CDG185-${randomBytes(4).toString('hex')}`;
  const shipmentId = insertUnrecordedShipment(forwarder, reference);

  await signIn(page, forwarder.email, '/freight-forwarder');
  await openDetail(page, '/freight-forwarder', shipmentId, reference);

  const history = historySection(page);
  await expect(history.getByText(earlierChanges)).toBeVisible();
  await expect(history.getByText(noChanges)).toHaveCount(0);
  await expect(history.getByRole('listitem')).toHaveCount(1);
  await expect(history.getByRole('listitem').filter({ hasText: 'Created' })).toContainText('by Priya Forwarder');
});
