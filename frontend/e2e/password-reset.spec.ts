import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? 'drift';
const mailpit = process.env.DRIFT_MAILPIT_URL ?? 'http://127.0.0.1:8025';
const sent = 'If this email is registered, a reset link has been sent';
const invalidLink = 'This reset link is invalid or has expired. Please request a new one.';
const limited = 'Too many password reset requests. Try again later.';
const password = 'Example123';
const replacement = 'Example124';
const created: string[] = [];

function sql(query: string, variables: Record<string, string> = {}) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'drift', '-d', database,
    '-v', 'ON_ERROR_STOP=1', '-At', ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])],
    { cwd: root, input: query, encoding: 'utf8' }).trim();
}

async function registeredAccount(request: APIRequestContext) {
  const token = randomBytes(32).toString('base64url');
  const email = `cdg118-e2e-${randomBytes(8).toString('hex')}@example.com`;
  created.push(email);
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = 'HARBOURLINE_DEMO';`, { email, hash: createHash('sha256').update(token).digest('hex') });
  const response = await request.post('/api/register', { data: { fullName: 'Alex Tan', email, password, invitationToken: token } });
  expect(response.status()).toBe(201);
  return email;
}

async function requestReset(page: Page, email: string) {
  await page.goto('/forgot-password');
  await page.getByLabel('Work email').fill(email);
  await page.getByRole('button', { name: /Send reset link/ }).click();
}

async function resetMail(email: string) {
  let subject = '';
  let text = '';
  await expect.poll(async () => {
    const found = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
    if (!found.ok) return '';
    const body = await found.json() as { messages?: { ID: string }[] };
    const id = body.messages?.[0]?.ID;
    if (!id) return '';
    const message = await fetch(`${mailpit}/api/v1/message/${id}`);
    if (!message.ok) return '';
    const detail = await message.json() as { Subject?: string; Text?: string };
    subject = detail.Subject ?? '';
    text = detail.Text ?? '';
    return text;
  }).toContain('#token=');
  const token = text.match(/#token=([A-Za-z0-9_-]{43})/)?.[1];
  expect(token, text).toBeTruthy();
  return { subject, text, token: token ?? '' };
}

test.afterAll(() => {
  for (const email of created.splice(0)) {
    sql(`DELETE FROM password_reset_tokens WHERE user_id IN (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
});

test('an unregistered email gets the same confirmation and no reset email', async ({ page }) => {
  const email = `cdg118-missing-${randomBytes(8).toString('hex')}@example.com`;
  await requestReset(page, email);
  await expect(page.getByRole('status')).toHaveText(sent);
  const found = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
  expect(found.ok).toBe(true);
  const body = await found.json() as { messages?: unknown[] };
  expect(body.messages ?? []).toHaveLength(0);
});

test('a reset email sets a new password and ends the previous session', async ({ page, request }) => {
  const email = await registeredAccount(request);
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');

  await requestReset(page, email);
  await expect(page.getByRole('status')).toHaveText(sent);
  const mail = await resetMail(email);
  expect(mail.subject).toBe('Reset your DRIFT password');
  expect(mail.text).toContain('15 minutes');

  await page.goto(`/reset-password#token=${mail.token}`);
  await page.getByLabel('New password').fill('short1A');
  await page.getByLabel('Confirm password').fill('short1A');
  await page.getByRole('button', { name: /Reset password/ }).click();
  await expect(page.getByText('Password must be at least 8 characters')).toBeVisible();
  await page.getByLabel('New password').fill(replacement);
  await page.getByLabel('Confirm password').fill(password);
  await page.getByRole('button', { name: /Reset password/ }).click();
  await expect(page.getByText('Passwords do not match')).toBeVisible();
  await page.getByLabel('Confirm password').fill(replacement);
  await page.getByRole('button', { name: /Reset password/ }).click();
  await expect(page).toHaveURL('/login');
  await expect(page.getByRole('status')).toHaveText('Your password has been reset');

  await page.goto('/freight-forwarder');
  await expect(page).toHaveURL('/login');
  await expect(page.getByRole('alert')).toHaveText('Your session has ended. Log in again.');

  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(replacement);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');
  await expect(page.getByRole('heading', { name: 'Shipment portfolio' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page.getByRole('alert')).toHaveText('Invalid email or password.');
});

test('an invalid or expired reset link cannot change the password', async ({ page, request }) => {
  await page.goto('/reset-password');
  await expect(page.getByRole('alert')).toHaveText(invalidLink);
  await expect(page.getByLabel('New password')).toHaveCount(0);

  const email = await registeredAccount(request);
  await requestReset(page, email);
  await expect(page.getByRole('status')).toHaveText(sent);
  const mail = await resetMail(email);
  expect(sql(`UPDATE password_reset_tokens AS t
    SET expires_at = NOW() - INTERVAL '1 day'
    FROM users AS u
    WHERE u.id = t.user_id AND u.email = :'email';`, { email })).toBe('UPDATE 1');

  await page.goto(`/reset-password#token=${mail.token}`);
  await page.getByLabel('New password').fill(replacement);
  await page.getByLabel('Confirm password').fill(replacement);
  await page.getByRole('button', { name: /Reset password/ }).click();
  await expect(page.getByRole('alert')).toHaveText(invalidLink);
  await expect(page.getByLabel('New password')).toHaveCount(0);
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(replacement);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page.getByRole('alert')).toHaveText('Invalid email or password.');
});

test('repeated reset requests are rate limited', async ({ page, request }) => {
  const email = await registeredAccount(request);
  for (let attempt = 0; attempt < 5; attempt += 1) {
    await requestReset(page, email);
    await expect(page.getByRole('status')).toHaveText(sent);
  }
  await requestReset(page, email);
  await expect(page.getByRole('alert')).toHaveText(limited);
  await expect(page.getByRole('status')).toHaveCount(0);
});
