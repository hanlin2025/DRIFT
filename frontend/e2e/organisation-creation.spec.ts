import { test, expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg313_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:organisation-creation. It only writes to an owned disposable database.');
  }
  expect(sql('SELECT current_database();')).toBe(database);
  expect(sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();")).toBe(ownerToken);
}

function sql(query: string, variables: Record<string, string> = {}) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  if (useDocker) return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common], { cwd: root, input: query, encoding: 'utf8' }).trim();
  return execFileSync('psql', ['-h', '127.0.0.1', ...common], { cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' } }).trim();
}

type Account = { email: string; token: string; home: string };

async function expectMessage(response: APIResponse, status: number, message: string) {
  const body = await response.text();
  expect(response.status(), body).toBe(status);
  expect(JSON.parse(body)).toEqual({ message });
}

async function register(request: APIRequestContext, role: 'FREIGHT_FORWARDER' | 'IMPORTER', company: string, fullName: string): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg313-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour' FROM companies WHERE code = :'company';`,
    { email, role, hash: createHash('sha256').update(invitation).digest('hex'), company });
  expect((await request.post('/api/register', { data: { fullName, email, password, invitationToken: invitation } })).status()).toBe(201);
  const login = await request.post('/api/login', { data: { email, password } });
  expect(login.status()).toBe(200);
  return { email, token: (await login.json()).token as string, home: role === 'IMPORTER' ? '/importer' : '/freight-forwarder' };
}

async function registerAdmin(request: APIRequestContext, company: string): Promise<Account> {
  const account = await register(request, 'FREIGHT_FORWARDER', company, 'Casey Admin');
  sql(`UPDATE users SET role_id = (SELECT id FROM roles WHERE code = 'ADMIN') WHERE email = :'email';`, { email: account.email });
  const login = await request.post('/api/login', { data: { email: account.email, password } });
  expect(login.status()).toBe(200);
  const body = await login.json() as { token: string; role: string };
  expect(body.role).toBe('ADMIN');
  return { ...account, token: body.token, home: '/admin' };
}

async function signIn(page: Page, account: Account) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(account.home, { timeout: 20_000 });
}

test.beforeAll(assertOwnedDatabase);

test('an administrator adds an organisation that another company can select and nobody is invited', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(180_000);
  const admin = await registerAdmin(request, 'HARBOURLINE_DEMO');
  const forwarder = await register(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO', 'Straits Forwarder');
  const suffix = randomBytes(3).toString('hex').toUpperCase();
  const code = `PACIFIC${suffix}`;
  const name = `Pacific Gate ${suffix}`;
  const companiesBefore = Number(sql('SELECT COUNT(*) FROM companies'));
  const relationshipsBefore = Number(sql('SELECT COUNT(*) FROM forwarder_importer_relationships'));
  const shipmentsBefore = Number(sql('SELECT COUNT(*) FROM shipments'));
  const invitationsBefore = Number(sql('SELECT COUNT(*) FROM invitations'));

  await expectMessage(await request.post('/api/admin/organisations', {
    headers: { Authorization: `Bearer ${forwarder.token}` },
    data: { code, name },
  }), 403, 'Only an administrator can assign users');
  await expectMessage(await request.post('/api/admin/organisations', { data: { code, name } }), 401, 'Your session has ended. Log in again.');

  await signIn(page, admin);
  await expect(page.getByRole('heading', { name: 'Add an organisation' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('button', { name: /Add organisation/ })).toBeDisabled();
  await page.getByLabel('Organisation code').fill('   ');
  await page.getByLabel('Organisation name').fill(name);
  await expect(page.getByRole('button', { name: /Add organisation/ })).toBeDisabled();
  await page.getByLabel('Organisation code').fill(code.toLowerCase());
  await page.getByRole('button', { name: /Add organisation/ }).click();
  await expect(page.getByRole('status')).toContainText(`${name} (${code}) was added.`);
  await expect(page.getByLabel('Organisation', { exact: true })).not.toContainText(name);
  await expect(page.getByLabel('Invitation organisation')).toContainText(name);

  expect(Number(sql('SELECT COUNT(*) FROM companies'))).toBe(companiesBefore + 1);
  expect(Number(sql('SELECT COUNT(*) FROM forwarder_importer_relationships'))).toBe(relationshipsBefore);
  expect(Number(sql('SELECT COUNT(*) FROM shipments'))).toBe(shipmentsBefore);
  expect(Number(sql('SELECT COUNT(*) FROM invitations'))).toBe(invitationsBefore);
  expect(sql(`SELECT COUNT(*) FROM users WHERE company_id = (SELECT id FROM companies WHERE code = :'code')`, { code })).toBe('0');
  expect(sql(`SELECT name FROM companies WHERE code = :'code'`, { code })).toBe(name);
  expect(sql("SELECT name FROM companies WHERE code = 'HARBOURLINE_DEMO'")).toBe('Harbourline Logistics (Demo)');
  expect(sql("SELECT name FROM companies WHERE code = 'STRAITS_FRESH_DEMO'")).toBe('Straits Fresh Imports (Demo)');

  await page.getByLabel('Organisation code').fill(code);
  await page.getByLabel('Organisation name').fill('Replacement name');
  await page.getByRole('button', { name: /Add organisation/ }).click();
  await expect(page.getByRole('alert')).toHaveText('An organisation with this code already exists.');
  expect(sql(`SELECT COUNT(*) FROM companies WHERE code = :'code'`, { code })).toBe('1');
  expect(sql(`SELECT name FROM companies WHERE code = :'code'`, { code })).toBe(name);

  const created = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${forwarder.token}` },
    data: {
      shipmentReference: `CDG313-${suffix}`, origin: 'Rotterdam', destination: 'Jakarta', transshipmentPort: 'Singapore',
      motherVessel: 'KOTA LARIS', motherVesselImo: '9351048', plannedMotherArrivalAt: '2026-10-28T23:00:00+08:00',
      feederVessel: 'Synthetic Feeder', plannedFeederDepartureAt: '2026-10-29T12:00:00+08:00',
    },
  });
  expect(created.status(), await created.text()).toBe(201);
  const shipmentId = (await created.json()).id as number;
  sql(`INSERT INTO forwarder_importer_relationships
      (forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
    SELECT shipment.company_id, company.id, TRUE, NOW(), actor.id, NOW(), actor.id
    FROM shipments shipment
    JOIN companies company ON company.code = :'code'
    JOIN users actor ON actor.email = :'email'
    WHERE shipment.id = :'shipment'
    ON CONFLICT (forwarder_company_id, importer_company_id)
    DO UPDATE SET active = TRUE, updated_at = NOW(), updated_by_user_id = EXCLUDED.updated_by_user_id;`,
  { code, email: forwarder.email, shipment: String(shipmentId) });
  const lookup = await request.get(`/api/organisations?type=importer&name=${encodeURIComponent(name)}&page=0&size=20`, {
    headers: { Authorization: `Bearer ${forwarder.token}` },
  });
  expect(lookup.status()).toBe(200);
  expect(await lookup.json()).toMatchObject({ total: 1, items: [{ code, name }] });

  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, forwarder);
  await page.goto(`/freight-forwarder/shipments/${shipmentId}`);
  const edit = page.getByRole('button', { name: 'Edit shipment' });
  const unreachable = page.getByRole('alert').filter({ hasText: 'We could not reach DRIFT' });
  await expect(edit.or(unreachable)).toBeVisible({ timeout: 20_000 });
  if (await unreachable.isVisible()) {
    await unreachable.getByRole('button', { name: 'Try again' }).click();
  }
  await expect(edit).toBeVisible({ timeout: 20_000 });
  await edit.click();
  const field = page.getByLabel('Link importer');
  await field.click();
  await field.fill(`No such ${suffix}`);
  await expect(page.getByRole('listbox', { name: 'Importer organisations' })).toContainText('No matching organisations.');
  await field.fill(name);
  const option = page.getByRole('option', { name: new RegExp(name) });
  await expect(option).toBeVisible();
  await expect(option).toContainText(code);
  await option.click();
  await expect(field).toHaveValue(name);
  expect(sql('SELECT COUNT(*) FROM forwarder_importer_relationships')).toBe(String(relationshipsBefore + 1));
  expect(sql(`SELECT COUNT(*) FROM shipments WHERE importer_company_id = (SELECT id FROM companies WHERE code = :'code')`, { code })).toBe('0');
  expect(sql(`SELECT COUNT(*) FROM shipments WHERE id = :'id' AND importer_company_id IS NULL`, { id: String(shipmentId) })).toBe('1');
});
