import { test, expect } from '@playwright/test';
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

test('invited user registers, reaches login and cannot reuse the invitation', async ({ page, request }, testInfo) => {
  const token = randomBytes(32).toString('base64url');
  const email = `cdg17-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, organisation_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour'
    FROM organisations WHERE code = 'HARBOURLINE_DEMO';`, { email, hash: createHash('sha256').update(token).digest('hex') });
  try {
    await page.goto(`/signup#invite=${token}`);
    await expect(page.getByText('Harbourline Logistics (Demo)')).toBeVisible();
    await expect(page.getByLabel(/Work email/)).toHaveValue(email);
    await expect(page.getByLabel(/Work email/)).not.toBeEditable();
    await expect(page.getByLabel(/Your role/)).toHaveValue('Freight forwarder');
    await page.getByRole('button', { name: /Create account/ }).click();
    await expect(page.getByText('Enter your full name.')).toBeVisible();
    await page.getByLabel('Full name').fill('Alex Tan');
    await page.getByLabel('Password', { exact: true }).fill('Example123');
    await page.getByLabel('Confirm password').fill('Different123');
    await page.getByRole('button', { name: /Create account/ }).click();
    await expect(page.getByText('Your passwords do not match.')).toBeVisible();
    await page.getByLabel('Confirm password').fill('Example123');
    await page.getByLabel('Full name').focus();
    await expect(page.getByText('Your passwords do not match.')).not.toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('signup.png'), fullPage: true });
    await page.getByRole('button', { name: /Create account/ }).click();
    await expect(page).toHaveURL('/login');
    await expect(page.getByText('Account created successfully.')).toBeVisible();
    await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
    expect(sql("SELECT status FROM invitations WHERE email = :'email';", { email })).toBe('CONSUMED');
    const reused = await request.post('/api/invitations/resolve', { data: { token } });
    expect(reused.status()).toBe(400);
    await page.screenshot({ path: testInfo.outputPath('login.png'), fullPage: true });
  } finally {
    sql("DELETE FROM invitations WHERE email = :'email'; DELETE FROM users WHERE email = :'email';", { email });
  }
});

test('missing and invalid invitations do not reveal a company or enable registration', async ({ page }) => {
  await page.goto('/signup');
  await expect(page.getByLabel('Invitation code')).toBeVisible();
  await page.getByLabel('Invitation code').fill('z'.repeat(43));
  await page.getByRole('button', { name: /Continue with invitation/ }).click();
  await expect(page.getByRole('alert')).toContainText('invitation');
  await expect(page.getByLabel('Full name')).toHaveCount(0);
});
