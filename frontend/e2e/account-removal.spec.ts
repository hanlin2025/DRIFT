import { test, expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const SESSION_ENDED = 'Your session has ended. Log in again.';
const INVALID_CREDENTIALS = 'Invalid email or password.';
const ADMIN_ONLY = 'Only an administrator can assign users';
const USER_NOT_FOUND = 'User not found.';
const OWN_ACCOUNT = 'You cannot remove your own account.';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg159_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:account-removal. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the account removal runner. Refusing to write fixtures.');
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
  expect(JSON.parse(body)).toEqual({ message });
}

async function registerAccount(
  request: APIRequestContext,
  role: 'FREIGHT_FORWARDER' | 'IMPORTER',
  company: string,
  fullName: string,
): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg159-e2e-${randomBytes(8).toString('hex')}@example.com`;
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

async function registerAdmin(request: APIRequestContext, company: string, fullName: string): Promise<Account> {
  const seeded = await registerAccount(request, 'FREIGHT_FORWARDER', company, fullName);
  sql(`UPDATE users SET role_id = (SELECT id FROM roles WHERE code = 'ADMIN') WHERE email = :'email';`, { email: seeded.email });
  const admin = await signInApi(request, seeded.email);
  expect(admin.role).toBe('ADMIN');
  expect(admin.companyCode).toBe(company);
  return admin;
}

function accountStatus(email: string) {
  return sql(`SELECT status FROM users WHERE email = :'email';`, { email });
}

async function remove(request: APIRequestContext, token: string, userId: number) {
  return request.patch(`/api/admin/users/${userId}/remove`, { headers: bearer(token) });
}

function cleanup(emails: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM account_status_audits WHERE operator_user_id = (SELECT id FROM users WHERE email = :'email')
        OR target_user_id = (SELECT id FROM users WHERE email = :'email');`, { email });
  }
  for (const email of emails) {
    sql(`DELETE FROM user_assignment_audits WHERE operator_user_id = (SELECT id FROM users WHERE email = :'email')
        OR target_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
}

async function fillLogin(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
}

async function signIn(page: Page, email: string, url: string) {
  await fillLogin(page, email);
  await expect(page).toHaveURL(url);
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('an administrator removes a user in their company and the removal is recorded', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account removal is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Alice Tan ${suffix}`);
  try {
    await signIn(page, administrator.email, '/admin');
    await expect(page.getByRole('heading', { name: 'User management' })).toBeVisible();
    const row = page.getByRole('radio', { name: new RegExp(target.fullName) });
    await expect(row).toHaveAccessibleName(/Active/);
    await row.check();

    await page.getByRole('button', { name: `Remove ${target.fullName}` }).click();
    const dialog = page.getByRole('dialog', { name: 'Remove user' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(target.email);
    await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(dialog).toHaveCount(0);

    await expect(page.getByRole('status')).toHaveText(`${target.fullName}'s account was removed.`);
    await expect(row).toHaveAccessibleName(/Removed/);
    await expect(page.getByRole('button', { name: `Remove ${target.fullName}` })).toHaveCount(0);
    const history = page.getByRole('region', { name: 'Removal history' });
    const entry = history.getByRole('listitem').filter({ hasText: `${administrator.fullName} removed ${target.fullName}` });
    await expect(entry).toHaveCount(1);
    await expect(entry).toContainText('Active → Removed');
    expect(accountStatus(target.email)).toBe('REMOVED');

    await page.reload();
    await expect(page).toHaveURL('/admin');
    await page.locator('#user-status-filter').selectOption('REMOVED');
    await expect(page.getByRole('radio', { name: new RegExp(target.fullName) })).toHaveAccessibleName(/Removed/);
    await page.locator('#user-status-filter').selectOption('ACTIVE');
    await expect(page.getByRole('radio', { name: new RegExp(target.fullName) })).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'Removal history' }).getByRole('listitem')
      .filter({ hasText: `${administrator.fullName} removed ${target.fullName}` })).toHaveCount(1);

    const audits = await request.get('/api/admin/account-status-audits', { headers: bearer(administrator.token) });
    const auditBody = await audits.text();
    expect(audits.status(), auditBody).toBe(200);
    const recorded = (JSON.parse(auditBody) as { operator: { id: number }; target: { id: number }; previousStatus: string; newStatus: string }[])
      .filter(item => item.target.id === target.id);
    expect(recorded).toEqual([expect.objectContaining({ operator: expect.objectContaining({ id: administrator.id }), previousStatus: 'ACTIVE', newStatus: 'REMOVED' })]);
  } finally {
    cleanup([administrator.email, target.email]);
  }
});

test('a removed user loses their open session and cannot sign in again', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account removal is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Dana Ong ${suffix}`);
  try {
    await signIn(page, target.email, '/freight-forwarder');
    await expect(page.getByRole('heading', { name: 'Shipment portfolio' })).toBeVisible();

    const removed = await remove(request, administrator.token, target.id);
    const removedBody = await removed.text();
    expect(removed.status(), removedBody).toBe(200);
    expect(JSON.parse(removedBody).status).toBe('REMOVED');

    await expectMessage(await request.get('/api/session', { headers: bearer(target.token) }), 401, SESSION_ENDED);
    await page.reload();
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('alert')).toHaveText(SESSION_ENDED);

    await fillLogin(page, target.email);
    await expect(page.getByRole('alert')).toHaveText(INVALID_CREDENTIALS);
    await expect(page).toHaveURL('/login');
    await expectMessage(await request.post('/api/login', { data: { email: target.email, password } }), 401, INVALID_CREDENTIALS);
  } finally {
    cleanup([administrator.email, target.email]);
  }
});

test('an administrator cannot remove their own account', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account removal is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const colleague = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Evan Koh ${suffix}`);
  try {
    await signIn(page, administrator.email, '/admin');
    await page.getByRole('radio', { name: new RegExp(colleague.fullName) }).check();
    await expect(page.getByRole('button', { name: `Remove ${colleague.fullName}` })).toBeVisible();
    await page.getByRole('radio', { name: new RegExp(administrator.fullName) }).check();
    await expect(page.getByRole('button', { name: `Remove ${administrator.fullName}` })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Remove / })).toHaveCount(0);

    await expectMessage(await remove(request, administrator.token, administrator.id), 400, OWN_ACCOUNT);
    expect(accountStatus(administrator.email)).toBe('ACTIVE');
    const stillSignedIn = await request.get('/api/session', { headers: bearer(administrator.token) });
    expect(stillSignedIn.status()).toBe(200);
  } finally {
    cleanup([administrator.email, colleague.email]);
  }
});

test('an administrator cannot see or remove users from another company', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account removal is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const outsider = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', `Blake Lim ${suffix}`);
  try {
    const users = await request.get('/api/admin/users', { headers: bearer(administrator.token) });
    const usersBody = await users.text();
    expect(users.status(), usersBody).toBe(200);
    expect(usersBody).not.toContain(outsider.email);

    await signIn(page, administrator.email, '/admin');
    await expect(page.locator('#user-status-filter')).toHaveValue('ALL');
    await expect(page.getByRole('radio', { name: new RegExp(administrator.fullName) })).toBeVisible();
    await expect(page.getByRole('radio', { name: new RegExp(outsider.fullName) })).toHaveCount(0);
    await expect(page.getByText(outsider.email)).toHaveCount(0);

    await expectMessage(await remove(request, administrator.token, outsider.id), 404, USER_NOT_FOUND);
    expect(accountStatus(outsider.email)).toBe('ACTIVE');
    const stillSignedIn = await request.get('/api/session', { headers: bearer(outsider.token) });
    expect(stillSignedIn.status()).toBe(200);
    const audits = await request.get('/api/admin/account-status-audits', { headers: bearer(administrator.token) });
    const auditBody = await audits.text();
    expect(audits.status(), auditBody).toBe(200);
    expect(auditBody).not.toContain(outsider.email);
  } finally {
    cleanup([administrator.email, outsider.email]);
  }
});

test('someone who is not an administrator cannot open user management or remove users', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account removal is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Fran Ong ${suffix}`);
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Gina Tan ${suffix}`);
  try {
    await signIn(page, forwarder.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: 'User management' })).toHaveCount(0);
    await page.goto('/admin');
    await expect(page).toHaveURL('/freight-forwarder');
    await expect(page.getByRole('heading', { name: 'User management' })).toHaveCount(0);

    await expectMessage(await remove(request, forwarder.token, target.id), 403, ADMIN_ONLY);
    await expectMessage(await request.get('/api/admin/account-status-audits', { headers: bearer(forwarder.token) }), 403, ADMIN_ONLY);
    await expectMessage(await request.patch(`/api/admin/users/${target.id}/remove`), 401, SESSION_ENDED);
    expect(accountStatus(target.email)).toBe('ACTIVE');
    const stillSignedIn = await request.get('/api/session', { headers: bearer(target.token) });
    expect(stillSignedIn.status()).toBe(200);
  } finally {
    cleanup([forwarder.email, target.email]);
  }
});
