import { test, expect, type APIRequestContext } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? 'drift';
function sql(query: string, variables: Record<string, string> = {}) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'drift', '-d', database,
    '-v', 'ON_ERROR_STOP=1', '-At', ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])],
    { cwd: root, input: query, encoding: 'utf8' }).trim();
}

const password = 'Example123';
const created: string[] = [];

async function registeredAccount(request: APIRequestContext, role: 'IMPORTER' | 'FREIGHT_FORWARDER', company: string) {
  const token = randomBytes(32).toString('base64url');
  const email = `cdg14-e2e-${randomBytes(8).toString('hex')}@example.com`;
  created.push(email);
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, { email, role, company, hash: createHash('sha256').update(token).digest('hex') });
  const response = await request.post('/api/register', { data: { fullName: 'Alex Tan', email, password, invitationToken: token } });
  expect(response.status()).toBe(201);
  return email;
}

test.afterAll(() => {
  for (const email of created.splice(0)) {
    sql("DELETE FROM invitations WHERE email = :'email'; DELETE FROM users WHERE email = :'email';", { email });
  }
});

test('an importer signs in to the shipment overview and signs out again', async ({ page, request }) => {
  const email = await registeredAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO');
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/importer');
  await expect(page.getByRole('heading', { name: 'Shipment overview' })).toBeVisible();
  await expect(page.getByText('Straits Fresh Imports (Demo)')).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL('/login');
  await page.goto('/importer');
  await expect(page).toHaveURL('/login');
});

test('a freight forwarder signs in to the managed shipment portfolio', async ({ page, request }) => {
  const email = await registeredAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email.toUpperCase());
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');
  await expect(page.getByRole('heading', { name: 'Shipment portfolio' })).toBeVisible();
  await expect(page.getByText('Harbourline Logistics (Demo)')).toBeVisible();
});

test('wrong passwords and unknown accounts are refused without a session', async ({ page, request }) => {
  const email = await registeredAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  await page.goto('/login');
  for (const [tryEmail, tryPassword] of [[email, 'WrongPass1'], [`missing-${email}`, password]]) {
    await page.getByLabel('Work email').fill(tryEmail);
    await page.getByLabel('Password', { exact: true }).fill(tryPassword);
    await page.getByRole('button', { name: /Log in/ }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid email or password.');
    await expect(page).toHaveURL('/login');
    expect(await page.evaluate(() => sessionStorage.getItem('drift.session'))).toBeNull();
  }
});

test('missing credentials show validation messages', async ({ page }) => {
  await page.goto('/login');
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page.getByText('Email is required')).toBeVisible();
  await expect(page.getByText('Password is required')).toBeVisible();
});

test('signed-out visitors are sent to login from protected pages', async ({ page }) => {
  for (const path of ['/importer', '/freight-forwarder']) {
    await page.goto(path);
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('heading', { name: 'Welcome to DRIFT.' })).toBeVisible();
  }
  const response = await page.request.get('/api/session');
  expect(response.status()).toBe(401);
});

test('an expired session must sign in again', async ({ page, request }) => {
  const email = await registeredAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');
  await page.evaluate(() => {
    const saved = JSON.parse(sessionStorage.getItem('drift.session') ?? '{}');
    sessionStorage.setItem('drift.session', JSON.stringify({ ...saved, expiresAt: '2020-01-01T00:00:00Z' }));
  });
  await page.reload();
  await expect(page).toHaveURL('/login');
  await expect(page.getByRole('alert')).toHaveText('Your session has ended. Log in again.');
  expect(await page.evaluate(() => sessionStorage.getItem('drift.session'))).toBeNull();
});
