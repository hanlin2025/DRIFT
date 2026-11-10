import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// CDG-366: company self-signup, email verification through real Mailpit mail, and assignment protections.
const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const mailpit = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025';
const PASSWORD = 'Str0ngPass1';
const UNVERIFIED = 'Verify your email before signing in. You can request a new verification link.';
const VERIFIED = 'Your email has been verified. You can now sign in.';
const INVALID_LINK = 'This verification link is invalid or has expired. Please request a new one.';
const CODE_TAKEN = 'An organisation with this code already exists.';
const OWN_ASSIGNMENT = 'You cannot change your own organisation or role.';

const suffix = randomBytes(4).toString('hex');
const company = { code: `CDG366_${suffix}`.toUpperCase(), name: `Onboarding Imports ${suffix}` };
const admin = { name: 'Nadia Founder', email: `cdg366-admin-${suffix}@example.com` };
const colleague = { name: 'Omar Colleague', email: `cdg366-colleague-${suffix}@example.com` };
const seeded = { name: 'Seeded Forwarder', email: `cdg366-seeded-${suffix}@example.com` };
let verificationLink = '';
let seededShipment = '';

function sql(query: string, variables: Record<string, string> = {}) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  const named = spawnSync('docker', ['inspect', '-f', '{{.State.Running}}', 'drift-postgres'], { encoding: 'utf8' });
  const command = named.status === 0 && named.stdout.trim() === 'true'
    ? ['exec', '-i', 'drift-postgres', 'psql', ...common]
    : ['compose', 'exec', '-T', 'postgres', 'psql', ...common];
  return execFileSync('docker', command, { cwd: root, input: query, encoding: 'utf8' }).trim();
}

function assertOwnedDatabase() {
  if (!/^drift_cdg366_[0-9]{14}_[a-f0-9]{12}$/.test(database) || ownerToken.length !== 64) {
    throw new Error('Run this spec through npm run test:e2e:company-signup, which creates a disposable database.');
  }
  const comment = sql("SELECT COALESCE(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = :'name';", { name: database });
  if (comment !== ownerToken) throw new Error('This database has no matching ownership record from the runner. Refusing to write fixtures.');
}

type MailSummary = { ID: string; To: Array<{ Address: string }> };

async function mailsTo(email: string) {
  const response = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`);
  expect(response.ok).toBe(true);
  return ((await response.json()) as { messages?: MailSummary[] }).messages ?? [];
}

async function latestMailText(email: string, atLeast = 1) {
  let id = '';
  await expect.poll(async () => {
    const messages = await mailsTo(email);
    id = messages.length >= atLeast ? messages[0].ID : '';
    return id;
  }, { timeout: 20_000 }).not.toBe('');
  const message = await (await fetch(`${mailpit}/api/v1/message/${id}`)).json() as { Text: string };
  return message.Text;
}

async function signIn(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: /Log in/ }).click();
}

async function createCompany(page: Page, values: { code: string; name: string; fullName: string; email: string }) {
  await page.goto('/signup');
  await page.getByRole('button', { name: /Create a company/ }).click();
  await page.getByLabel('Company code').fill(values.code);
  await page.getByLabel('Company name').fill(values.name);
  await page.getByLabel('Full name').fill(values.fullName);
  await page.getByLabel('Work email').fill(values.email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
  await page.getByRole('button', { name: /Create company/ }).click();
}

async function registerSeededForwarder(request: APIRequestContext) {
  const invitation = randomBytes(32).toString('base64url');
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour' FROM companies WHERE code = 'HARBOURLINE_DEMO';`,
  { email: seeded.email, hash: createHash('sha256').update(invitation).digest('hex') });
  const registered = await request.post('/api/register', {
    data: { fullName: seeded.name, email: seeded.email, password: PASSWORD, invitationToken: invitation },
  });
  expect(registered.status(), await registered.text()).toBe(201);
  const login = await request.post('/api/login', { data: { email: seeded.email, password: PASSWORD } });
  expect(login.status()).toBe(200);
  return (await login.json()).token as string;
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  assertOwnedDatabase();
});

test.beforeEach(({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'Company onboarding is covered once on desktop.');
});

test('a new company administrator is blocked until the emailed link verifies them', { tag: '@disposable-database' }, async ({ page }) => {
  test.setTimeout(90_000);
  await createCompany(page, { ...company, fullName: admin.name, email: admin.email });
  await expect(page.getByRole('status').filter({ hasText: 'Check your email' }))
    .toContainText('Check your email to verify your account before signing in.');
  expect(sql("SELECT active FROM companies WHERE code = :'code';", { code: company.code })).toBe('f');
  expect(sql("SELECT status FROM users WHERE email = :'email';", { email: admin.email })).toBe('PENDING_VERIFICATION');

  await signIn(page, admin.email);
  await expect(page).toHaveURL('/login');
  await expect(page.getByRole('alert')).toContainText(UNVERIFIED);
  await expect(page.getByRole('button', { name: 'Resend the verification email' })).toBeVisible();

  const text = await latestMailText(admin.email);
  verificationLink = text.match(/https?:\/\/\S+\/verify-email#token=[A-Za-z0-9_-]{43}/)?.[0] ?? '';
  expect(verificationLink, 'the email must contain a verification link with the token in the fragment').not.toBe('');
  const token = verificationLink.split('#token=')[1];
  expect(sql('SELECT COUNT(*) FROM email_verification_tokens WHERE token_hash = :\'token\';', { token })).toBe('0');

  await page.goto(verificationLink);
  await expect(page.getByText(VERIFIED)).toBeVisible();
  await expect(page).toHaveTitle('Verify email | DRIFT');
  expect(new URL(page.url()).hash).toBe('');
  expect(sql("SELECT active FROM companies WHERE code = :'code';", { code: company.code })).toBe('t');
  expect(sql("SELECT status FROM users WHERE email = :'email';", { email: admin.email })).toBe('ACTIVE');
});

test('replaying the verification link shows the invalid-link message', { tag: '@disposable-database' }, async ({ page }) => {
  expect(verificationLink).not.toBe('');
  await page.goto(verificationLink);
  await expect(page.getByRole('alert')).toHaveText(INVALID_LINK);
  await expect(page.getByLabel('Signup email')).toBeVisible();
});

test('a taken company code shows the field error and creates nothing', { tag: '@disposable-database' }, async ({ page }) => {
  const duplicate = `cdg366-duplicate-${suffix}@example.com`;
  await createCompany(page, { code: company.code.toLowerCase(), name: 'Copycat Imports', fullName: 'Copy Cat', email: duplicate });
  const code = page.getByLabel('Company code');
  await expect(page.locator('#company-companyCode-error')).toHaveText(CODE_TAKEN);
  await expect(code).toHaveAttribute('aria-invalid', 'true');
  await expect(code).toHaveValue(company.code.toLowerCase());
  expect(sql("SELECT COUNT(*) FROM companies WHERE UPPER(code) = :'code';", { code: company.code })).toBe('1');
  expect(sql("SELECT COUNT(*) FROM companies WHERE name = 'Copycat Imports';")).toBe('0');
  expect(sql("SELECT COUNT(*) FROM users WHERE email = :'email';", { email: duplicate })).toBe('0');
  expect(await mailsTo(duplicate)).toHaveLength(0);
});

test('the verified administrator sees only their company and cannot change their own assignment', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(90_000);
  const seededToken = await registerSeededForwarder(request);
  const shipment = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${seededToken}` },
    data: {
      shipmentReference: `CDG366-${suffix}`, origin: 'Rotterdam', destination: 'Jakarta', transshipmentPort: 'Singapore',
      motherVessel: 'Seeded Vessel', plannedMotherArrivalAt: '2026-12-15T08:00:00+08:00',
      feederVessel: 'Seeded Feeder', plannedFeederDepartureAt: '2026-12-16T12:00:00+08:00',
    },
  });
  expect(shipment.status(), await shipment.text()).toBe(201);
  seededShipment = String((await shipment.json()).id);

  await signIn(page, admin.email);
  await expect(page).toHaveURL('/admin');
  await expect(page.locator('.workspace-identity span')).toHaveText(company.name);
  await expect(page.getByRole('radio')).toHaveCount(1);
  await expect(page.getByRole('radio', { name: new RegExp(admin.name) })).toHaveAccessibleName(/Administrator/);

  const scoped = await page.evaluate(async shipmentId => {
    const get = (path: string) => fetch(path, { credentials: 'include' });
    const users = await get('/api/admin/users');
    const organisations = await get('/api/admin/organisations');
    const shipments = await get('/api/shipments');
    const seededDetail = await get(`/api/shipments/${shipmentId}`);
    return {
      users: await users.json(), organisations: await organisations.json(),
      shipments: await shipments.json(), seededStatus: seededDetail.status,
    };
  }, seededShipment);
  expect(scoped.users.map((user: { email: string }) => user.email)).toEqual([admin.email]);
  expect(scoped.organisations.map((item: { code: string }) => item.code)).toEqual([company.code]);
  expect(JSON.stringify(scoped.shipments)).not.toContain(`CDG366-${suffix}`);
  expect([403, 404]).toContain(scoped.seededStatus);

  await page.getByRole('radio', { name: new RegExp(admin.name) }).check();
  await expect(page.locator('#assignment-organisation')).toBeDisabled();
  await expect(page.locator('#assignment-role')).toBeDisabled();
  await expect(page.getByRole('button', { name: /Save assignment/ })).toBeDisabled();
  await expect(page.locator('#assignment-role')).toHaveAccessibleDescription(OWN_ASSIGNMENT);

  const ownId = Number(sql("SELECT id FROM users WHERE email = :'email';", { email: admin.email }));
  const companyId = Number(sql("SELECT id FROM companies WHERE code = :'code';", { code: company.code }));
  const patched = await page.evaluate(async ({ ownId, companyId }) => {
    const response = await fetch(`/api/admin/users/${ownId}/assign`, {
      method: 'PATCH', credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-DRIFT-Request': '1' },
      body: JSON.stringify({ organisationId: companyId, role: 'IMPORTER' }),
    });
    return { status: response.status, body: await response.json() };
  }, { ownId, companyId });
  expect(patched).toEqual({ status: 409, body: { message: OWN_ASSIGNMENT } });
  await page.reload();
  await expect(page).toHaveURL('/admin');
  await expect(page.getByRole('radio', { name: new RegExp(admin.name) })).toHaveAccessibleName(/Administrator/);
  expect(sql("SELECT roles.code FROM users JOIN roles ON roles.id = users.role_id WHERE users.email = :'email';", { email: admin.email })).toBe('ADMIN');

  const seededView = await request.get('/api/shipments', { headers: { Authorization: `Bearer ${seededToken}` } });
  expect(seededView.status()).toBe(200);
  expect(JSON.stringify(await seededView.json())).not.toContain(company.code);
  const seededAdminList = await request.get('/api/admin/users', { headers: { Authorization: `Bearer ${seededToken}` } });
  expect(seededAdminList.status()).toBe(403);
});

test('the new administrator invites a colleague who joins with a locked email, company and role', { tag: '@disposable-database' }, async ({ page, browser }) => {
  test.setTimeout(90_000);
  await signIn(page, admin.email);
  await expect(page).toHaveURL('/admin');
  const companyId = sql("SELECT id FROM companies WHERE code = :'code';", { code: company.code });
  await page.getByLabel('Colleague email').fill(colleague.email);
  await page.getByLabel('Invitation organisation').selectOption(companyId);
  await page.getByLabel('Invitation role').selectOption('LOGISTICS_MANAGER');
  await page.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Invitation sent' })).toContainText(`Invitation sent to ${colleague.email}`);

  const text = await latestMailText(colleague.email);
  const code = text.match(/#invite=([A-Za-z0-9_-]{43})/)?.[1] ?? text.match(/[A-Za-z0-9_-]{43}/)?.[0] ?? '';
  expect(code).not.toBe('');

  const context = await browser.newContext();
  const joiner = await context.newPage();
  try {
    await joiner.goto(`/signup#invite=${code}`);
    await expect(joiner.getByText(company.name)).toBeVisible();
    await expect(joiner.getByLabel(/Email/)).toHaveValue(colleague.email);
    await expect(joiner.getByLabel(/Email/)).toHaveAttribute('readonly', '');
    await expect(joiner.getByLabel(/Your role/)).toHaveValue('Logistics manager');
    await expect(joiner.getByLabel(/Your role/)).toHaveAttribute('readonly', '');
    await expect(joiner.getByLabel('Company code')).toHaveCount(0);
    await joiner.getByLabel('Full name').fill(colleague.name);
    await joiner.getByLabel('Password', { exact: true }).fill(PASSWORD);
    await joiner.getByLabel('Confirm password').fill(PASSWORD);
    await joiner.getByRole('button', { name: /Create account/ }).click();
    await expect(joiner).toHaveURL('/login');
    await signIn(joiner, colleague.email);
    await expect(joiner).toHaveURL('/logistics-manager');
    await expect(joiner.locator('.workspace-identity span')).toHaveText(company.name);
  } finally {
    await context.close();
  }
  expect(sql("SELECT companies.code FROM users JOIN companies ON companies.id = users.company_id WHERE users.email = :'email';",
    { email: colleague.email })).toBe(company.code);
  expect(sql("SELECT status FROM users WHERE email = :'email';", { email: colleague.email })).toBe('ACTIVE');

  await page.reload();
  await expect(page.getByRole('radio')).toHaveCount(2);
  await expect(page.getByRole('radio', { name: new RegExp(colleague.name) })).toHaveAccessibleName(/Logistics manager/);
});
