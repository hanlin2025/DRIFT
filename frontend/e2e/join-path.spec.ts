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
  expect(credential.length).toBeGreaterThanOrEqual(32);
});

test('the first administrator can invite a colleague who stays signed in across tabs', { tag: '@disposable-database' }, async ({ page, request }) => {
  const stamp = Date.now();
  const adminEmail = `cdg311-admin-${stamp}@example.com`;
  const guest = `cdg311-guest-${stamp}@example.com`;
  const harbourline = sql("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'");
  const created = await request.post('/api/setup/administrator', {
    headers: { 'X-DRIFT-Setup-Token': credential },
    data: { organisationId: Number(harbourline), fullName: 'Ada Admin', email: adminEmail, password: 'Example123' },
  });
  expect(created.status()).toBe(201);

  await page.goto('/login');
  await page.getByLabel('Email').fill(adminEmail);
  await page.getByLabel('Password', { exact: true }).fill('Example123');
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/admin');
  await page.getByLabel('Colleague email').fill(guest);
  await page.getByLabel('Invitation organisation').selectOption(harbourline);
  await page.getByLabel('Invitation role').selectOption('LOGISTICS_MANAGER');
  await page.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.getByRole('status')).toContainText(`Invitation sent to ${guest}`);

  let messageId = '';
  await expect.poll(async () => {
    const response = await fetch('http://127.0.0.1:8025/api/v1/messages');
    const body = await response.json() as { messages?: Array<{ ID: string; To: Array<{ Address: string }> }> };
    messageId = body.messages?.find(item => item.To.some(to => to.Address === guest))?.ID ?? '';
    return messageId;
  }).not.toBe('');
  const mail = await (await fetch(`http://127.0.0.1:8025/api/v1/message/${messageId}`)).json() as { Text: string };
  const code = mail.Text.match(/[A-Za-z0-9_-]{43}/)?.[0] ?? '';
  expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain('eyJ');

  const colleague = await page.context().newPage();
  await colleague.goto(`/signup#invite=${code}`);
  await colleague.getByLabel('Full name').fill('Casey Guest');
  await colleague.getByLabel('Password', { exact: true }).fill('Example123');
  await colleague.getByLabel('Confirm password').fill('Example123');
  await colleague.getByRole('button', { name: /Create account/ }).click();
  await expect(colleague).toHaveURL('/login');
  await colleague.getByLabel('Email').fill(guest);
  await colleague.getByLabel('Password', { exact: true }).fill('Example123');
  await colleague.getByRole('button', { name: /Log in/ }).click();
  await expect(colleague).toHaveURL('/logistics-manager');
  await expect(colleague.getByText('Harbourline Logistics (Demo)')).toBeVisible();

  const anotherTab = await page.context().newPage();
  await anotherTab.goto('/logistics-manager');
  await expect(anotherTab).toHaveURL('/logistics-manager');
  await expect(anotherTab.getByText('Casey Guest')).toBeVisible();
  await expect(anotherTab.getByText('Harbourline Logistics (Demo)')).toBeVisible();
  expect(await anotherTab.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain('eyJ');

  await colleague.getByRole('button', { name: 'Sign out' }).click();
  await expect(colleague).toHaveURL('/login');
  await anotherTab.reload();
  await expect(anotherTab).toHaveURL('/login');
  const replay = await anotherTab.request.get('/api/session');
  expect(replay.status()).toBe(401);
});
