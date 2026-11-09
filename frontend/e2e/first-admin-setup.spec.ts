import { test, expect } from '@playwright/test';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? 'drift';
const credential = process.env.DRIFT_SETUP_TOKEN ?? '';

function sql(query: string) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', query];
  const named = spawnSync('docker', ['inspect', '-f', '{{.State.Running}}', 'drift-postgres'], { encoding: 'utf8' });
  const command = named.status === 0 && named.stdout.trim() === 'true'
    ? ['exec', '-i', 'drift-postgres', 'psql', ...common]
    : ['compose', 'exec', '-T', 'postgres', 'psql', ...common];
  return execFileSync('docker', command, { cwd: root, encoding: 'utf8' }).trim();
}

test.beforeAll(() => {
  expect(credential.length, 'DRIFT_SETUP_TOKEN is required for the setup browser test').toBeGreaterThanOrEqual(32);
});

test('an operator creates the one initial administrator and can sign in', { tag: '@disposable-database' }, async ({ page }) => {
  const email = `cdg309-browser-${Date.now()}@example.com`;
  await page.goto('/setup');
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('alert')).toHaveText('Enter the setup credential.');

  await page.getByLabel('Setup credential').fill('not-the-operator-credential');
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('alert')).toHaveText(/missing or invalid/);
  expect(sql("SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.code = 'ADMIN'")).toBe('0');

  await page.getByLabel('Setup credential').fill(credential);
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByLabel('Organisation')).toBeVisible();
  await page.getByRole('button', { name: /Create administrator/ }).click();
  await expect(page.getByText('Choose an organisation.')).toBeVisible();

  await page.getByLabel('Organisation').selectOption({ label: 'Harbourline Logistics (Demo)' });
  await page.getByLabel('Full name').fill('Ada Admin');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('short');
  await page.getByLabel('Confirm password').fill('short');
  await page.getByRole('button', { name: /Create administrator/ }).click();
  await expect(page.getByText('Your password must meet all four requirements below.')).toBeVisible();

  await page.getByLabel('Password', { exact: true }).fill('Example123');
  await page.getByLabel('Confirm password').fill('Example123');
  await page.getByRole('button', { name: /Create administrator/ }).click();
  await expect(page.getByRole('status')).toContainText('Ada Admin is the administrator for Harbourline Logistics (Demo)');
  expect(await page.evaluate(() => sessionStorage.getItem('drift.session'))).toBeNull();
  expect(await page.evaluate(secret => JSON.stringify(sessionStorage).includes(secret) || document.body.innerText.includes(secret), credential)).toBe(false);

  await page.getByRole('link', { name: 'Log in' }).click();
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('Example123');
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/admin');
  await expect(page.getByRole('heading', { name: 'User management' })).toBeVisible();

  await page.goto('/setup');
  await page.getByLabel('Setup credential').fill(credential);
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('status')).toHaveText(/already has an administrator/);
  expect(sql("SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.code = 'ADMIN'")).toBe('1');
  expect(sql('SELECT COUNT(*) FROM installation_setup')).toBe('1');
});
