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

async function mailpitMessages() {
  const response = await fetch('http://127.0.0.1:8025/api/v1/messages');
  if (!response.ok) throw new Error(`Mailpit returned ${response.status}`);
  const body = await response.json() as { messages?: Array<{ ID: string; To: Array<{ Address: string }> }> };
  return body.messages ?? [];
}

test.beforeAll(() => {
  expect(credential.length, 'DRIFT_SETUP_TOKEN is required for the invitation browser test').toBeGreaterThanOrEqual(32);
});

test('an administrator invites a colleague and the invitation registers once', { tag: '@disposable-database' }, async ({ page, request }) => {
  const stamp = Date.now();
  const adminEmail = `cdg310-browser-admin-${stamp}@example.com`;
  const guest = `cdg310-browser-guest-${stamp}@example.com`;
  const harbourline = sql("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'");
  const straits = sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'");

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
  await expect(page.getByRole('heading', { name: 'Invite a colleague' })).toBeVisible();

  await page.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.getByRole('alert')).toHaveText('Enter an email, organisation and role.');

  const organisations = await page.getByLabel('Invitation organisation').locator('option').allTextContents();
  expect(organisations.join('\n')).toContain('Harbourline Logistics (Demo)');
  expect(organisations.join('\n')).not.toContain('Straits Fresh Imports (Demo)');

  await page.getByLabel('Colleague email').fill(guest);
  await page.getByLabel('Invitation organisation').selectOption(harbourline);
  await page.getByLabel('Invitation role').selectOption('IMPORTER');

  spawnSync('docker', ['stop', 'drift-mailpit'], { encoding: 'utf8' });
  try {
    await page.getByRole('button', { name: 'Send invitation' }).click();
    await expect(page.getByRole('alert')).toHaveText('The invitation email could not be sent. No invitation was issued.');
    expect(sql(`SELECT COUNT(*) FROM invitations WHERE email = '${guest}'`)).toBe('0');
  } finally {
    const started = spawnSync('docker', ['start', 'drift-mailpit'], { encoding: 'utf8' });
    if (started.status !== 0) throw new Error(started.stderr || 'Mailpit did not start again');
  }
  await expect.poll(async () => {
    const health = await fetch('http://127.0.0.1:8025/api/v1/info').then(response => response.ok).catch(() => false);
    return health;
  }).toBe(true);

  await page.getByLabel('Colleague email').fill(guest);
  await page.getByLabel('Invitation organisation').selectOption(harbourline);
  await page.getByLabel('Invitation role').selectOption('LOGISTICS_MANAGER');
  await page.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.getByRole('status')).toContainText(`Invitation sent to ${guest}`);

    const foreign = await page.request.post('/api/admin/invitations', {
      headers: { 'X-DRIFT-Request': '1' },
      data: { organisationId: Number(straits), role: 'IMPORTER', email: `cdg310-foreign-${stamp}@example.com` },
    });
  expect(foreign.status()).toBe(400);
  expect(sql(`SELECT COUNT(*) FROM invitations WHERE email = 'cdg310-foreign-${stamp}@example.com'`)).toBe('0');

  let messageId = '';
  await expect.poll(async () => {
    const messages = await mailpitMessages();
    messageId = messages.find(item => item.To.some(to => to.Address === guest))?.ID ?? '';
    return messageId;
  }).not.toBe('');
  const detail = await fetch(`http://127.0.0.1:8025/api/v1/message/${messageId}`);
  const mail = await detail.json() as { Text: string };
  const code = mail.Text.match(/[A-Za-z0-9_-]{43}/)?.[0] ?? '';
  expect(code).toHaveLength(43);
  expect(mail.Text).toContain(`#invite=${encodeURIComponent(code)}`);
  expect(await page.evaluate(secret => document.body.innerText.includes(secret), code)).toBe(false);

  await page.goto(`/signup#invite=${code}`);
  await expect(page.getByLabel(/Email/)).toHaveValue(guest);
  await expect(page.getByLabel(/Your role/)).toHaveValue('Logistics manager');
  await page.getByLabel('Full name').fill('Casey Guest');
  await page.getByLabel('Password', { exact: true }).fill('Example123');
  await page.getByLabel('Confirm password').fill('Example123');
  await page.getByRole('button', { name: /Create account/ }).click();
  await expect(page).toHaveURL('/login');

  const replay = await request.post('/api/register', {
    data: { fullName: 'Casey Guest', email: guest, password: 'Example123', invitationToken: code },
  });
  expect(replay.status()).toBe(400);
  expect(sql(`SELECT status FROM invitations WHERE email = '${guest}'`)).toBe('CONSUMED');

  await page.goto('/login');
  await page.getByLabel('Email').fill(guest);
  await page.getByLabel('Password', { exact: true }).fill('Example123');
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/logistics-manager');
});
