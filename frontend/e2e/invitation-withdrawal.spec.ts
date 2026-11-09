import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { expect, test, type APIRequestContext } from '@playwright/test';

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

function code() {
  return randomBytes(32).toString('base64url');
}

function hash(token: string) {
  return createHash('sha256').update(token).digest('hex');
}

function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function login(request: APIRequestContext, email: string) {
  const response = await request.post('/api/login', { data: { email, password: 'Example123' } });
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  return JSON.parse(body) as { token: string };
}

test.beforeAll(() => {
  expect(credential.length, 'DRIFT_SETUP_TOKEN is required for the withdrawal browser test').toBeGreaterThanOrEqual(32);
});

test('an administrator confirms withdrawal and the invitation cannot register', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(90_000);
  const stamp = Date.now();
  const adminEmail = `cdg330-browser-admin-${stamp}@example.com`;
  const otherEmail = `cdg330-browser-other-${stamp}@example.com`;
  const forwarderEmail = `cdg330-browser-forwarder-${stamp}@example.com`;
  const guest = `cdg330-browser-guest-${stamp}@example.com`;
  const expiredEmail = `cdg330-browser-expired-${stamp}@example.com`;
  const acceptedEmail = `cdg330-browser-accepted-${stamp}@example.com`;
  const foreignEmail = `cdg330-browser-foreign-${stamp}@example.com`;
  const guestCode = code();
  const expiredCode = code();
  const acceptedCode = code();
  const foreignCode = code();
  const harbourline = sql("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'");
  const straits = sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'");

  const created = await request.post('/api/setup/administrator', {
    headers: { 'X-DRIFT-Setup-Token': credential },
    data: { organisationId: Number(harbourline), fullName: 'Ada Admin', email: adminEmail, password: 'Example123' },
  });
  expect(created.status(), await created.text()).toBe(201);

  const copyUser = (email: string, role: string, companyId: string) => sql(`
    INSERT INTO users (full_name, email, password_hash, role_id, company_id)
    SELECT 'Coverage User', '${email}', password_hash, r.id, ${companyId}
    FROM users u JOIN roles r ON r.code = '${role}'
    WHERE u.email = '${adminEmail}'
  `);
  copyUser(otherEmail, 'ADMIN', straits);
  copyUser(forwarderEmail, 'FREIGHT_FORWARDER', harbourline);
  copyUser(acceptedEmail, 'IMPORTER', harbourline);
  const insertInvitation = (email: string, companyId: string, role: string, token: string, expiry: string, status: string) => sql(`
    INSERT INTO invitations (email, company_id, role, token_hash, expires_at, status, consumed_at)
    VALUES ('${email}', ${companyId}, '${role}', '${hash(token)}', ${expiry}, '${status}',
      CASE WHEN '${status}' = 'CONSUMED' THEN NOW() ELSE NULL END)
  `);
  insertInvitation(guest, harbourline, 'FREIGHT_FORWARDER', guestCode, "NOW() + INTERVAL '2 days'", 'PENDING');
  insertInvitation(expiredEmail, harbourline, 'IMPORTER', expiredCode, "NOW() - INTERVAL '1 hour'", 'PENDING');
  insertInvitation(acceptedEmail, harbourline, 'IMPORTER', acceptedCode, "NOW() + INTERVAL '2 days'", 'CONSUMED');
  insertInvitation(foreignEmail, straits, 'LOGISTICS_MANAGER', foreignCode, "NOW() + INTERVAL '2 days'", 'PENDING');
  const guestId = sql(`SELECT id FROM invitations WHERE email = '${guest}'`);
  const expiredId = sql(`SELECT id FROM invitations WHERE email = '${expiredEmail}'`);
  const acceptedId = sql(`SELECT id FROM invitations WHERE email = '${acceptedEmail}'`);
  const foreignId = sql(`SELECT id FROM invitations WHERE email = '${foreignEmail}'`);
  const acceptedHash = sql(`SELECT password_hash FROM users WHERE email = '${acceptedEmail}'`);

  const admin = await login(request, adminEmail);
  const other = await login(request, otherEmail);
  const forwarder = await login(request, forwarderEmail);

  const listed = await request.get('/api/admin/invitations', { headers: bearer(admin.token) });
  const listedBody = await listed.text();
  expect(listed.status(), listedBody).toBe(200);
  expect(listedBody).toContain(guest);
  expect(listedBody).toContain(expiredEmail);
  expect(listedBody).not.toContain(acceptedEmail);
  expect(listedBody).not.toContain(foreignEmail);
  expect(listedBody).not.toContain(guestCode);
  expect(listedBody).not.toContain(expiredCode);
  expect(listedBody).not.toContain(foreignCode);

  const anonymousList = await request.get('/api/admin/invitations');
  expect(anonymousList.status()).toBe(401);
  const anonymousWithdraw = await request.post(`/api/admin/invitations/${guestId}/withdraw`);
  expect(anonymousWithdraw.status()).toBe(401);
  const forwarderList = await request.get('/api/admin/invitations', { headers: bearer(forwarder.token) });
  expect(forwarderList.status()).toBe(403);
  const forwarderWithdraw = await request.post(`/api/admin/invitations/${guestId}/withdraw`, { headers: bearer(forwarder.token) });
  expect(forwarderWithdraw.status()).toBe(403);

  const otherList = await request.get('/api/admin/invitations', { headers: bearer(other.token) });
  const otherBody = await otherList.text();
  expect(otherList.status(), otherBody).toBe(200);
  expect(otherBody).toContain(foreignEmail);
  expect(otherBody).not.toContain(guest);
  expect(otherBody).not.toContain(foreignCode);
  const hidden = await request.post(`/api/admin/invitations/${guestId}/withdraw`, { headers: bearer(other.token) });
  const missing = await request.post('/api/admin/invitations/999999999/withdraw', { headers: bearer(admin.token) });
  const hiddenBody = await hidden.text();
  const missingBody = await missing.text();
  expect(hidden.status()).toBe(403);
  expect(missing.status()).toBe(403);
  expect(hiddenBody).toBe(missingBody);
  expect(hiddenBody).not.toContain(guest);

  const expiredWithdraw = await request.post(`/api/admin/invitations/${expiredId}/withdraw`, { headers: bearer(admin.token) });
  expect(expiredWithdraw.status()).toBe(409);
  expect(await expiredWithdraw.json()).toMatchObject({ message: 'This invitation has expired.' });
  expect(sql(`SELECT status FROM invitations WHERE id = ${expiredId}`)).toBe('PENDING');
  expect(sql(`SELECT COUNT(*) FROM invitation_revocations WHERE invitation_id = ${expiredId}`)).toBe('0');
  const expiredRegister = await request.post('/api/register', {
    data: { fullName: 'Late Guest', email: expiredEmail, password: 'Example123', invitationToken: expiredCode },
  });
  expect(expiredRegister.status()).toBe(400);
  expect(sql(`SELECT COUNT(*) FROM users WHERE email = '${expiredEmail}'`)).toBe('0');

  const acceptedWithdraw = await request.post(`/api/admin/invitations/${acceptedId}/withdraw`, { headers: bearer(admin.token) });
  expect(acceptedWithdraw.status()).toBe(409);
  expect(await acceptedWithdraw.json()).toMatchObject({ message: 'This invitation has already been accepted.' });
  expect(sql(`SELECT password_hash FROM users WHERE email = '${acceptedEmail}'`)).toBe(acceptedHash);
  expect(sql(`SELECT status FROM invitations WHERE id = ${acceptedId}`)).toBe('CONSUMED');
  expect(sql(`SELECT COUNT(*) FROM invitation_revocations WHERE invitation_id = ${acceptedId}`)).toBe('0');
  expect(sql(`SELECT status FROM invitations WHERE id = ${guestId}`)).toBe('PENDING');

  await page.goto('/login');
  await page.getByLabel('Email').fill(adminEmail);
  await page.getByLabel('Password', { exact: true }).fill('Example123');
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/admin');
  const pending = page.getByRole('region', { name: 'Pending invitations' });
  await expect(pending.getByRole('heading', { name: 'Pending invitations' })).toBeVisible();
  await expect(pending.getByText(guest)).toBeVisible();
  await expect(pending.getByText(expiredEmail)).toBeVisible();
  await expect(pending.getByText('Expired', { exact: true })).toBeVisible();
  await expect(pending.getByText('Pending', { exact: true })).toBeVisible();
  await expect(pending.getByText('Harbourline Logistics (Demo)').first()).toBeVisible();
  await expect(pending.getByText(foreignEmail)).toHaveCount(0);
  await expect(pending.getByText(acceptedEmail)).toHaveCount(0);
  expect(await page.evaluate(secret => document.body.innerText.includes(secret), guestCode)).toBe(false);

  let blocked = false;
  await page.route(/\/api\/admin\/invitations\/\d+\/withdraw$/, async route => {
    if (!blocked) {
      blocked = true;
      await route.abort();
      return;
    }
    await route.continue();
  });
  await page.getByRole('button', { name: `Withdraw invitation for ${guest}` }).click();
  await expect(page.getByRole('dialog')).toContainText('They will not be able to create an account');
  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(sql(`SELECT status FROM invitations WHERE id = ${guestId}`)).toBe('PENDING');

  await page.getByRole('button', { name: `Withdraw invitation for ${guest}` }).click();
  await page.getByRole('button', { name: 'Withdraw', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('still pending');
  expect(sql(`SELECT status FROM invitations WHERE id = ${guestId}`)).toBe('PENDING');
  expect(sql(`SELECT COUNT(*) FROM invitation_revocations WHERE invitation_id = ${guestId}`)).toBe('0');
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByRole('status').filter({ hasText: `Invitation for ${guest} withdrawn.` })).toBeVisible();
  expect(sql(`SELECT status FROM invitations WHERE id = ${guestId}`)).toBe('REVOKED');
  expect(sql(`SELECT COUNT(*) FROM invitation_revocations WHERE invitation_id = ${guestId}`)).toBe('1');
  expect(sql(`SELECT operator_user_id = (SELECT id FROM users WHERE email = '${adminEmail}') FROM invitation_revocations WHERE invitation_id = ${guestId}`)).toBe('t');
  expect(await page.evaluate(secret => document.body.innerText.includes(secret), guestCode)).toBe(false);

  await page.goto(`/signup#invite=${encodeURIComponent(guestCode)}`);
  await expect(page.getByRole('alert')).toHaveText('This invitation is no longer valid.');
  await expect(page.getByRole('alert')).not.toContainText(/account exists|already exists/i);
  await expect(page.getByLabel('Full name')).toHaveCount(0);
  const replay = await request.post('/api/register', {
    data: { fullName: 'Casey Guest', email: guest, password: 'Example123', invitationToken: guestCode },
  });
  expect(replay.status()).toBe(400);
  expect(await replay.json()).toMatchObject({ message: 'This invitation is no longer valid.' });
  expect(sql(`SELECT COUNT(*) FROM users WHERE email = '${guest}'`)).toBe('0');

  const again = await request.post(`/api/admin/invitations/${guestId}/withdraw`, { headers: bearer(admin.token) });
  expect(again.status()).toBe(409);
  expect(await again.json()).toMatchObject({ message: 'This invitation is already withdrawn.' });
  expect(sql(`SELECT COUNT(*) FROM invitation_revocations WHERE invitation_id = ${guestId}`)).toBe('1');
});
