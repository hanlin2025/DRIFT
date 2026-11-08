import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const container = process.env.DRIFT_E2E_CONTAINER ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';

test.use({ timezoneId: 'Asia/Singapore' });

type Account = { email: string; token: string; role: 'FREIGHT_FORWARDER' | 'IMPORTER' };

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg204_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken) || container !== 'drift-cdg204-pg') {
    throw new Error('Run this spec through npm run test:e2e:deadline. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the deadline runner. Refusing to write fixtures.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  const common = ['exec', '-i', '-e', 'PGPASSWORD=drift', container, 'psql', '-h', '127.0.0.1', '-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  return execFileSync('docker', common, {
    cwd: root, input: query, encoding: 'utf8',
  }).trim();
}

async function register(request: APIRequestContext, companyCode: string, role: Account['role']): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg204-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company: companyCode, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName: 'Alex Tan', email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  return { email, token: (await signedIn.json()).token as string, role };
}

async function signIn(page: Page, account: Account) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(account.role === 'IMPORTER' ? '/importer' : '/freight-forwarder');
}

function fact(page: Page) {
  return page.locator('.route-facts > dl > div').filter({ has: page.getByText('Mother-vessel arrival deadline', { exact: true }) });
}

function cleanup(emails: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM shipment_risk_alert_deliveries
      WHERE user_id = (SELECT id FROM users WHERE email = :'email')
         OR transition_id IN (
           SELECT transitions.id FROM shipment_risk_transitions transitions
           WHERE transitions.shipment_id IN (
             SELECT id FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email')));
      DELETE FROM shipment_risk_transitions
      WHERE shipment_id IN (SELECT id FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email'));
      DELETE FROM shipment_risk_states
      WHERE shipment_id IN (SELECT id FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email'));
      DELETE FROM shipment_importer_constraints
      WHERE shipment_id IN (SELECT id FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email'));
      DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('an importer saves, reloads, updates, and clears a transshipment arrival deadline', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The deadline page is covered once on desktop.');
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const importer = await register(request, 'STRAITS_FRESH_DEMO', 'IMPORTER');
  try {
    const created = await request.post('/api/shipments', {
      headers: { Authorization: `Bearer ${forwarder.token}` },
      data: {
        shipmentReference: `CDG204-${randomBytes(4).toString('hex')}`,
        origin: 'Shanghai',
        destination: 'Jakarta',
        transshipmentPort: 'Singapore',
        motherVessel: 'Ever Steady',
        plannedMotherArrivalAt: '2026-10-15T08:00:00+08:00',
        feederVessel: 'Straits Feeder',
        plannedFeederDepartureAt: '2026-10-15T17:00:00+08:00',
      },
    });
    expect(created.status(), await created.text()).toBe(201);
    const shipmentId = (await created.json()).id as number;
    const importerCompanyId = Number(sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'"));
    const linked = await request.patch(`/api/shipments/${shipmentId}/link-importer`, {
      headers: { Authorization: `Bearer ${forwarder.token}` },
      data: { importerCompanyId },
    });
    expect(linked.status(), await linked.text()).toBe(200);

    await signIn(page, importer);
    await page.goto(`/importer/shipments/${shipmentId}`);
    await expect(fact(page)).toContainText('Not recorded');
    await expect(fact(page)).toContainText('not the final delivery time');
    await expect(fact(page).getByText('Confirming port: Singapore')).toBeVisible();

    await fact(page).getByLabel('Deadline').fill('2026-10-29T00:00');
    const savedResponse = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/transshipment-arrival-deadline'));
    await fact(page).getByRole('button', { name: 'Save deadline' }).click();
    const saved = await savedResponse;
    expect(saved.request().postDataJSON()).toMatchObject({
      transshipmentArrivalDeadline: '2026-10-29T00:00:00+08:00',
      expectedTransshipmentPort: 'Singapore',
    });
    const savedBody = await saved.json();
    expect(savedBody.transshipmentArrivalDeadline.deadline).toBe('2026-10-28T16:00:00Z');
    await expect(fact(page).locator('time')).toHaveAttribute('datetime', '2026-10-28T16:00:00Z');

    await page.reload();
    await expect(fact(page).locator('time')).toHaveAttribute('datetime', '2026-10-28T16:00:00Z');

    await fact(page).getByLabel('Deadline').fill('2026-10-30T08:30');
    const updatedResponse = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/transshipment-arrival-deadline'));
    await fact(page).getByRole('button', { name: 'Update deadline' }).click();
    const updated = await updatedResponse;
    expect((await updated.json()).transshipmentArrivalDeadline.deadline).toBe('2026-10-30T00:30:00Z');
    await page.reload();
    await expect(fact(page).locator('time')).toHaveAttribute('datetime', '2026-10-30T00:30:00Z');

    const clearedResponse = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/transshipment-arrival-deadline'));
    await fact(page).getByRole('button', { name: 'Clear deadline' }).click();
    expect((await clearedResponse).request().postDataJSON()).toEqual({
      transshipmentArrivalDeadline: null,
      expectedTransshipmentPort: 'Singapore',
    });
    await expect(fact(page)).toContainText('Not recorded');
    await page.reload();
    await expect(fact(page)).toContainText('Not recorded');
    await expect(fact(page).locator('time')).toHaveCount(0);
  } finally {
    cleanup([forwarder.email, importer.email]);
  }
});
