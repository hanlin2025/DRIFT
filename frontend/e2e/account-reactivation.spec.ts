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
const ALREADY_ACTIVE = 'This account is already active.';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg163_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:account-reactivation. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the account reactivation runner. Refusing to write fixtures.');
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
  const email = `cdg163-e2e-${randomBytes(8).toString('hex')}@example.com`;
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

function identity(email: string) {
  return sql(`SELECT users.status || '|' || users.session_generation::text || '|' || roles.code || '|' || companies.code || '|' || users.password_hash
    FROM users
    JOIN roles ON roles.id = users.role_id
    JOIN companies ON companies.id = users.company_id
    WHERE users.email = :'email';`, { email });
}

function reactivationAudits(email: string) {
  return sql(`SELECT COUNT(*) FROM account_status_audits
    WHERE target_user_id = (SELECT id FROM users WHERE email = :'email')
      AND previous_status = 'REMOVED' AND new_status = 'ACTIVE';`, { email });
}

async function remove(request: APIRequestContext, token: string, userId: number) {
  return request.patch(`/api/admin/users/${userId}/remove`, { headers: bearer(token) });
}

async function reactivate(request: APIRequestContext, token: string, userId: number) {
  return request.patch(`/api/admin/users/${userId}/reactivate`, { headers: bearer(token) });
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
  await page.getByLabel('Email').fill(email);
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

test('an administrator reactivates a removed user and that user signs in again', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account reactivation is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Alice Tan ${suffix}`);
  const preserved = identity(target.email);
  try {
    const removed = await remove(request, administrator.token, target.id);
    expect(removed.status(), await removed.text()).toBe(200);
    await expectMessage(await request.get('/api/session', { headers: bearer(target.token) }), 401, SESSION_ENDED);
    await expectMessage(await request.post('/api/login', { data: { email: target.email, password } }), 401, INVALID_CREDENTIALS);

    await signIn(page, administrator.email, '/admin');
    await expect(page.getByRole('heading', { name: 'User management' })).toBeVisible();
    await page.locator('#user-status-filter').selectOption('REMOVED');
    const row = page.getByRole('radio', { name: new RegExp(target.fullName) });
    await expect(row).toHaveAccessibleName(/Removed/);
    await row.check();
    await expect(page.getByLabel('Organisation')).toBeDisabled();
    await expect(page.getByLabel('Role')).toBeDisabled();
    await expect(page.getByRole('button', { name: /Save assignment/ })).toBeDisabled();

    await page.getByRole('button', { name: `Reactivate ${target.fullName}` }).click();
    const dialog = page.getByRole('dialog', { name: 'Reactivate user' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(target.email);
    const box = await dialog.boundingBox();
    const viewport = page.viewportSize();
    expect(box).toBeTruthy();
    expect(viewport).toBeTruthy();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.y).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width + 1);
    expect(box!.y + box!.height).toBeLessThanOrEqual(viewport!.height + 1);
    await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Reactivate', exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toHaveCount(0);
    expect(identity(target.email).startsWith('REMOVED|')).toBe(true);
    expect(reactivationAudits(target.email)).toBe('0');

    await page.getByRole('button', { name: `Reactivate ${target.fullName}` }).click();
    await page.getByRole('dialog', { name: 'Reactivate user' }).getByRole('button', { name: 'Reactivate', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('status')).toHaveText(`${target.fullName}'s account was reactivated.`);
    await expect(page.getByRole('radio', { name: new RegExp(target.fullName) })).toHaveCount(0);
    await page.locator('#user-status-filter').selectOption('ACTIVE');
    await expect(page.getByRole('radio', { name: new RegExp(target.fullName) })).toHaveAccessibleName(/Active/);
    await expect(page.getByRole('button', { name: `Reactivate ${target.fullName}` })).toHaveCount(0);

    const history = page.getByRole('region', { name: 'Account status history' });
    const entry = history.getByRole('listitem').filter({ hasText: `${administrator.fullName} reactivated ${target.fullName}` });
    await expect(entry).toHaveCount(1);
    await expect(entry).toContainText('Removed → Active');
    const restored = identity(target.email);
    const [, , role, company, hash] = preserved.split('|');
    expect(restored).toBe(`ACTIVE|1|${role}|${company}|${hash}`);
    expect(reactivationAudits(target.email)).toBe('1');
    await expectMessage(await request.get('/api/session', { headers: bearer(target.token) }), 401, SESSION_ENDED);

    await page.reload();
    await expect(page).toHaveURL('/admin');
    await expect(page.getByRole('region', { name: 'Account status history' }).getByRole('listitem')
      .filter({ hasText: `${administrator.fullName} reactivated ${target.fullName}` })).toHaveCount(1);

    const audits = await request.get('/api/admin/account-status-audits', { headers: bearer(administrator.token) });
    const auditBody = await audits.text();
    expect(audits.status(), auditBody).toBe(200);
    const recorded = (JSON.parse(auditBody) as { operator: { id: number }; target: { id: number }; previousStatus: string; newStatus: string; recordedAt: string }[])
      .filter(item => item.target.id === target.id && item.newStatus === 'ACTIVE');
    expect(recorded).toEqual([expect.objectContaining({
      operator: expect.objectContaining({ id: administrator.id }),
      previousStatus: 'REMOVED',
      newStatus: 'ACTIVE',
      recordedAt: expect.any(String),
    })]);

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL('/login');
    await fillLogin(page, target.email);
    await expect(page).toHaveURL('/freight-forwarder');
    const session = await request.get('/api/session', { headers: bearer((await signInApi(request, target.email)).token) });
    const sessionBody = await session.text();
    expect(session.status(), sessionBody).toBe(200);
    expect(JSON.parse(sessionBody)).toEqual(expect.objectContaining({
      id: target.id,
      role: 'FREIGHT_FORWARDER',
      company: expect.objectContaining({ id: target.companyId, code: 'HARBOURLINE_DEMO' }),
    }));

    await expectMessage(await reactivate(request, administrator.token, target.id), 409, ALREADY_ACTIVE);
    expect(reactivationAudits(target.email)).toBe('1');
    expect(identity(target.email)).toBe(restored);
  } finally {
    cleanup([administrator.email, target.email]);
  }
});

test('a rejected reactivation shows the API message and leaves the row removed', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account reactivation is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const target = await registerAccount(request, 'IMPORTER', 'HARBOURLINE_DEMO', `Ben Ong ${suffix}`);
  try {
    expect((await remove(request, administrator.token, target.id)).status()).toBe(200);
    await signIn(page, administrator.email, '/admin');
    await page.locator('#user-status-filter').selectOption('REMOVED');
    await page.getByRole('radio', { name: new RegExp(target.fullName) }).check();
    await page.getByRole('button', { name: `Reactivate ${target.fullName}` }).click();
    const raced = await reactivate(request, administrator.token, target.id);
    expect(raced.status(), await raced.text()).toBe(200);
    await page.getByRole('dialog', { name: 'Reactivate user' }).getByRole('button', { name: 'Reactivate', exact: true }).click();

    await expect(page.getByRole('alert')).toHaveText(ALREADY_ACTIVE);
    await expect(page.getByRole('radio', { name: new RegExp(target.fullName) })).toHaveAccessibleName(/Removed/);
    expect(reactivationAudits(target.email)).toBe('1');
  } finally {
    cleanup([administrator.email, target.email]);
  }
});

test('an administrator cannot reactivate a user in another organisation', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account reactivation is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const otherAdmin = await registerAdmin(request, 'STRAITS_FRESH_DEMO', `Other Admin ${suffix}`);
  const outsider = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', `Blake Lim ${suffix}`);
  try {
    expect((await remove(request, otherAdmin.token, outsider.id)).status()).toBe(200);
    await signIn(page, administrator.email, '/admin');
    await page.locator('#user-status-filter').selectOption('REMOVED');
    await expect(page.getByRole('radio', { name: new RegExp(outsider.fullName) })).toHaveCount(0);
    await expectMessage(await reactivate(request, administrator.token, outsider.id), 404, USER_NOT_FOUND);
    expect(identity(outsider.email).startsWith('REMOVED|')).toBe(true);
    expect(reactivationAudits(outsider.email)).toBe('0');
  } finally {
    cleanup([administrator.email, otherAdmin.email, outsider.email]);
  }
});

test('someone who is not an administrator cannot reactivate users', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Account reactivation is covered once on desktop.');
  test.setTimeout(90_000);
  const suffix = randomBytes(3).toString('hex');
  const administrator = await registerAdmin(request, 'HARBOURLINE_DEMO', `Casey Admin ${suffix}`);
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Fran Ong ${suffix}`);
  const target = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', `Gina Tan ${suffix}`);
  try {
    expect((await remove(request, administrator.token, target.id)).status()).toBe(200);
    await signIn(page, forwarder.email, '/freight-forwarder');
    await expect(page.getByRole('link', { name: 'User management' })).toHaveCount(0);
    await page.goto('/admin');
    await expect(page).toHaveURL('/freight-forwarder');
    await expect(page.getByRole('button', { name: /Reactivate/ })).toHaveCount(0);
    await expectMessage(await reactivate(request, forwarder.token, target.id), 403, ADMIN_ONLY);
    expect(identity(target.email).startsWith('REMOVED|')).toBe(true);
    expect(reactivationAudits(target.email)).toBe('0');
  } finally {
    cleanup([administrator.email, forwarder.email, target.email]);
  }
});
