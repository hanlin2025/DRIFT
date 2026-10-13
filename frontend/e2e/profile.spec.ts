import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const replacement = 'FreshPass1';
const POLICY = 'Password must be at least 8 characters and include an uppercase letter, a lowercase letter, and a number, with no spaces';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg143_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:profile. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the profile runner. Refusing to write fixtures.');
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

type Account = { email: string; id: number; home: string; company: string; role: string };

async function registerAccount(
  request: APIRequestContext,
  role: 'IMPORTER' | 'FREIGHT_FORWARDER',
  company: string,
  fullName = 'Alex Tan',
): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg143-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName, email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  const token = (await signedIn.json()).token as string;
  const profile = await request.get('/api/profile', { headers: { Authorization: `Bearer ${token}` } });
  expect(profile.status()).toBe(200);
  const view = await profile.json() as { id: number };
  return {
    email,
    id: view.id,
    home: role === 'IMPORTER' ? '/importer' : '/freight-forwarder',
    company: company === 'HARBOURLINE_DEMO' ? 'Harbourline Logistics (Demo)' : 'Straits Fresh Imports (Demo)',
    role: role === 'IMPORTER' ? 'Importer' : 'Freight forwarder',
  };
}

function cleanup(emails: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
}

async function signIn(page: Page, email: string, secret = password, home = '/freight-forwarder') {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(secret);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(home);
}

async function openProfile(page: Page) {
  await page.getByRole('link', { name: 'Profile' }).click();
  await expect(page).toHaveURL('/profile');
  await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();
}

async function sessionToken(page: Page) {
  return page.evaluate(() => {
    const saved = sessionStorage.getItem('drift.session');
    if (!saved) return '';
    const parsed = JSON.parse(saved) as { token?: string };
    return parsed.token ?? '';
  });
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test.describe.configure({ timeout: 60_000 });

test('the saved name and notification choice are still there after signing out', { tag: '@disposable-database' }, async ({ page, request }) => {
  const account = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  try {
    await signIn(page, account.email);
    await openProfile(page);
    await expect(page.getByLabel('Full name')).toHaveValue('Alex Tan');
    await expect(page.getByLabel('Email', { exact: true })).toHaveValue(account.email);
    await expect(page.getByLabel('Email', { exact: true })).toHaveAttribute('readonly', '');
    await expect(page.getByLabel('Company')).toHaveValue(account.company);
    await expect(page.getByLabel('Company')).toHaveAttribute('readonly', '');
    await expect(page.getByLabel('Role')).toHaveValue(account.role);
    await expect(page.getByLabel('Role')).toHaveAttribute('readonly', '');
    await expect(page.getByLabel(/photo/i)).toHaveCount(0);
    await expect(page.getByRole('button', { name: /send/i })).toHaveCount(0);
    await expect(page.getByRole('list', { name: 'Password requirements' })).toBeVisible();
    await expect(page.getByText('At least 8 characters')).toBeVisible();
    await expect(page.getByText('Uppercase and lowercase')).toBeVisible();
    await expect(page.getByText('At least one number')).toBeVisible();
    await expect(page.getByText('No spaces')).toBeVisible();

    await page.getByLabel('Full name').fill('  Alicia Tan  ');
    await page.getByRole('checkbox', { name: 'Email me when a shipment risk reaches this level' }).check();
    await page.getByLabel('Minimum risk level').selectOption('CRITICAL');
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('status')).toHaveText('Profile saved.');
    await expect(page.getByLabel('Full name')).toHaveValue('Alicia Tan');
    await page.getByRole('link', { name: 'DRIFT home' }).click();
    await expect(page).toHaveURL('/freight-forwarder');
    await expect(page.getByText('Alicia Tan', { exact: true })).toBeVisible();
    await openProfile(page);
    await expect(page.getByLabel('Email', { exact: true })).toHaveValue(account.email);
    await expect(page.getByLabel('Company')).toHaveValue(account.company);
    await expect(page.getByLabel('Role')).toHaveValue('Freight forwarder');

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL('/login');
    await signIn(page, account.email);
    await expect(page.getByText('Alicia Tan', { exact: true })).toBeVisible();
    await openProfile(page);
    await expect(page.getByLabel('Full name')).toHaveValue('Alicia Tan');
    await expect(page.getByLabel('Email', { exact: true })).toHaveValue(account.email);
    await expect(page.getByLabel('Company')).toHaveValue(account.company);
    await expect(page.getByLabel('Role')).toHaveValue('Freight forwarder');
    await expect(page.getByRole('checkbox', { name: 'Email me when a shipment risk reaches this level' })).toBeChecked();
    await expect(page.getByLabel('Minimum risk level')).toHaveValue('CRITICAL');
    await page.reload();
    await expect(page.getByLabel('Full name')).toHaveValue('Alicia Tan');
    await expect(page.getByLabel('Minimum risk level')).toHaveValue('CRITICAL');
  } finally {
    cleanup([account.email]);
  }
});

test('a compliant password replaces the old one and a rejected password leaves it', { tag: '@disposable-database' }, async ({ page, request }) => {
  const account = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Mina Rahman');
  try {
    await signIn(page, account.email, password, account.home);
    await openProfile(page);
    await expect(page.getByLabel('Company')).toHaveValue('Straits Fresh Imports (Demo)');
    await expect(page.getByLabel('Role')).toHaveValue('Importer');

    await page.getByLabel('Current password').fill('WrongPass1');
    await page.getByLabel('New password').fill(replacement);
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('alert')).toHaveText('Current password is incorrect.');
    await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();
    await expect(page.getByLabel('Full name')).toHaveValue('Mina Rahman');

    await page.getByLabel('Current password').fill(password);
    await page.getByLabel('New password').fill('password1');
    await expect(page.locator('#profile-password-rules li[data-met=false]')).toHaveCount(1);
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('alert')).toHaveText(POLICY);
    await expect(page).toHaveURL('/profile');

    await page.getByLabel('Current password').fill(password);
    await page.getByLabel('New password').fill(replacement);
    await expect(page.locator('#profile-password-rules li[data-met=true]')).toHaveCount(4);
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('status')).toHaveText('Your password was changed. Log in with the new password.');
    await expect(page.getByRole('alert')).toHaveCount(0);

    await page.getByLabel('Work email').fill(account.email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /Log in/ }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid email or password.');
    await expect(page).toHaveURL('/login');

    await signIn(page, account.email, replacement, account.home);
    await openProfile(page);
    await expect(page.getByLabel('Full name')).toHaveValue('Mina Rahman');
    await expect(page.getByLabel('Email', { exact: true })).toHaveValue(account.email);
    await expect(page.getByLabel('Role')).toHaveValue('Importer');
  } finally {
    cleanup([account.email]);
  }
});

test('an empty or over-length name is rejected and the page stays up', { tag: '@disposable-database' }, async ({ page, request }) => {
  const account = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Nora Chen');
  try {
    await signIn(page, account.email);
    await openProfile(page);

    await page.getByLabel('Full name').fill('');
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('alert')).toHaveText('Full name is required');
    await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();

    await page.getByLabel('Full name').fill('   ');
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('alert')).toHaveText('Full name is required');

    await page.getByLabel('Full name').fill('n'.repeat(201));
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('alert')).toHaveText('Full name must be at most 200 characters');
    await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();

    await page.reload();
    await expect(page.getByLabel('Full name')).toHaveValue('Nora Chen');
    await expect(page.getByLabel('Email', { exact: true })).toHaveValue(account.email);
    await page.getByRole('button', { name: 'Sign out' }).click();
    await signIn(page, account.email);
    await openProfile(page);
    await expect(page.getByLabel('Full name')).toHaveValue('Nora Chen');
  } finally {
    cleanup([account.email]);
  }
});

test('one signed-in user cannot open or change another user profile', { tag: '@disposable-database' }, async ({ page, request }) => {
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Owner Tan');
  const other = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Other Lim');
  try {
    await signIn(page, owner.email);
    await openProfile(page);
    await page.getByLabel('Full name').fill('Owner Renamed');
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('status')).toHaveText('Profile saved.');
    await page.getByRole('button', { name: 'Sign out' }).click();

    await signIn(page, other.email, password, other.home);
    await openProfile(page);
    await expect(page.getByLabel('Full name')).toHaveValue('Other Lim');
    await expect(page.getByLabel('Email', { exact: true })).toHaveValue(other.email);
    await expect(page.getByLabel('Company')).toHaveValue(other.company);
    await expect(page.getByLabel('Role')).toHaveValue('Importer');
    await expect(page.getByText(owner.email)).toHaveCount(0);
    await expect(page.getByText('Owner Renamed')).toHaveCount(0);
    await expect(page.locator('input[name="userId"], input[name="email"]:not([readonly])')).toHaveCount(0);

    const forbidden = await page.request.patch('/api/profile', {
      headers: { Authorization: `Bearer ${await sessionToken(page)}` },
      data: { userId: owner.id, fullName: 'Hijacked Name' },
    });
    expect(forbidden.status()).toBe(403);
    expect((await forbidden.json()).message).toBe('You can only update your own profile.');
    await expect(page.getByLabel('Full name')).toHaveValue('Other Lim');
    await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();

    await page.goto(`/profile/${owner.id}`);
    await expect(page).toHaveURL(other.home);
    await expect(page.getByText(owner.email)).toHaveCount(0);
    await expect(page.getByText('Owner Renamed')).toHaveCount(0);

    await page.getByRole('button', { name: 'Sign out' }).click();
    await signIn(page, owner.email);
    await openProfile(page);
    await expect(page.getByLabel('Full name')).toHaveValue('Owner Renamed');
    await expect(page.getByLabel('Email', { exact: true })).toHaveValue(owner.email);
  } finally {
    cleanup([owner.email, other.email]);
  }
});
