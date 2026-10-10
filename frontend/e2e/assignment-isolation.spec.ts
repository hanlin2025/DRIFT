import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const ASSIGNED = 'User assigned successfully';
const ASSIGNMENT_FORBIDDEN = 'Only an administrator can assign users';
const SESSION_ENDED = 'Your session has ended. Log in again.';
const NO_ACCESS = 'You do not have access to this shipment';
const EMPTY_LIST = 'No shipments yet. Shipments registered for your company will appear here.';

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

type Account = { email: string; token: string; id: number; fullName: string; companyId: number };

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
  expect(signedIn.status()).toBe(200);
  const body = await signedIn.json();
  expect(body.token).toEqual(expect.any(String));
  expect(body.id).toEqual(expect.any(Number));
  expect(body.company.id).toEqual(expect.any(Number));
  return { email, token: body.token, id: body.id, fullName: body.fullName, companyId: body.company.id };
}

function promoteToAdmin(email: string) {
  sql(`UPDATE users SET role_id = (SELECT id FROM roles WHERE code = 'ADMIN') WHERE email = :'email';`, { email });
}

async function createShipment(request: APIRequestContext, token: string) {
  const shipmentReference = `CDG112-${randomBytes(4).toString('hex')}`;
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference,
      origin: 'Rotterdam',
      destination: 'Jakarta',
      transshipmentPort: 'Singapore',
      motherVessel: 'Ever Steady',
      plannedMotherArrivalAt: '2026-10-15T08:00:00+08:00',
      feederVessel: 'Straits Feeder',
      plannedFeederDepartureAt: '2026-10-16T12:00:00+08:00',
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const body = await response.json();
  return { id: body.id as number, shipmentReference };
}

function cleanup(emails: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
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

test('an administrator assigns a role from user management and the previous session must log in again', { tag: '@disposable-database' }, async ({ page, browser, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(90_000);
  const member = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const shipment = await createShipment(request, member.token);
  const administrator = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Casey Admin');
  promoteToAdmin(administrator.email);
  const adminSession = await signInApi(request, administrator.email);
  expect(adminSession.fullName).toBe('Casey Admin');
  const adminPage = await browser.newPage();
  try {
    await signIn(page, member.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
    await signIn(adminPage, administrator.email, '/admin');
    await adminPage.getByRole('radio', { name: /Alice Tan/ }).check();
    await adminPage.locator('#assignment-organisation').selectOption({ label: 'Straits Fresh Imports (Demo)' });
    await adminPage.locator('#assignment-role').selectOption({ label: 'Importer' });
    await adminPage.getByRole('button', { name: /Save assignment/ }).click();
    await expect(adminPage.getByRole('status')).toHaveText(ASSIGNED);

    const ended = await request.get('/api/session', { headers: { Authorization: `Bearer ${member.token}` } });
    expect(ended.status()).toBe(401);
    expect(await ended.json()).toEqual({ message: SESSION_ENDED });

    await page.reload();
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('alert')).toHaveText(SESSION_ENDED);

    await signIn(page, member.email, '/importer');
    await expect(page.getByText(EMPTY_LIST)).toBeVisible();
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toHaveCount(0);
    const refreshed = await request.post('/api/login', { data: { email: member.email, password } });
    expect(refreshed.status()).toBe(200);
    const next = await refreshed.json();
    expect(next.role).toBe('IMPORTER');
    expect(next.company.code).toBe('STRAITS_FRESH_DEMO');
  } finally {
    await adminPage.close();
    cleanup([member.email, administrator.email]);
  }
});

test('a non-admin cannot use the assignment API and is sent away from user management', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(90_000);
  const importer = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  try {
    const assign = await request.patch(`/api/admin/users/${target.id}/assign`, {
      headers: { Authorization: `Bearer ${importer.token}` },
      data: { organisationId: importer.companyId, role: 'IMPORTER' },
    });
    expect(assign.status()).toBe(403);
    expect(await assign.json()).toEqual({ message: ASSIGNMENT_FORBIDDEN });
    const users = await request.get('/api/admin/users', { headers: { Authorization: `Bearer ${importer.token}` } });
    expect(users.status()).toBe(403);
    expect(await users.json()).toEqual({ message: ASSIGNMENT_FORBIDDEN });

    await signIn(page, importer.email, '/importer');
    await page.goto('/admin');
    await expect(page).toHaveURL('/importer');
    await expect(page.getByRole('heading', { name: 'User management' })).toHaveCount(0);
  } finally {
    cleanup([importer.email, target.email]);
  }
});

test('a user cannot see a shipment that belongs to another organisation', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Assignment is covered once on desktop.');
  test.setTimeout(90_000);
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Alice Tan');
  const outsider = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const shipment = await createShipment(request, owner.token);
  try {
    const list = await request.get('/api/shipments', { headers: { Authorization: `Bearer ${outsider.token}` } });
    expect(list.status()).toBe(200);
    const references = (await list.json() as { shipmentReference: string }[]).map(item => item.shipmentReference);
    expect(references).not.toContain(shipment.shipmentReference);
    const detail = await request.get(`/api/shipments/${shipment.id}`, { headers: { Authorization: `Bearer ${outsider.token}` } });
    expect(detail.status()).toBe(403);
    expect(await detail.json()).toEqual({ message: NO_ACCESS });

    await signIn(page, outsider.email, '/importer');
    await expect(page.getByText(EMPTY_LIST)).toBeVisible();
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toHaveCount(0);
    await signIn(page, owner.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: shipment.shipmentReference })).toBeVisible();
  } finally {
    cleanup([owner.email, outsider.email]);
  }
});
