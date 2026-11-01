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
const NO_IMPACT = 'No predicted impact on the recorded mother-vessel transshipment-arrival deadline.';
const EXCEEDS = 'The predicted arrival exceeds the recorded mother-vessel transshipment-arrival deadline.';
const MISSING_CONSTRAINT = 'No mother-vessel transshipment-arrival deadline is recorded for the current importer.';

test.use({ timezoneId: 'Asia/Singapore' });

type Account = { email: string; token: string; role: 'FREIGHT_FORWARDER' | 'IMPORTER' };
type Detail = {
  delayComparison: { outcome: string | null; reason: string | null; message: string; estimatedArrivalAt: string | null; deadline: string | null; recordedTransshipmentPort: string | null };
  arrivalComparison: { estimatedArrivalAt: string | null; difference: string | null; unavailableReason: { message: string } | null };
  transshipmentArrivalDeadline: { deadline: string; transshipmentPort: string } | null;
};

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg207_comparison_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken) || container !== 'drift-cdg207-pg') {
    throw new Error('Run this spec through npm run test:e2e:delay-comparison. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the delay-comparison runner. Refusing to write fixtures.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  const common = ['exec', '-i', '-e', 'PGPASSWORD=drift', container, 'psql', '-h', '127.0.0.1', '-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  return execFileSync('docker', common, { cwd: root, input: query, encoding: 'utf8' }).trim();
}

async function register(request: APIRequestContext, companyCode: string, role: Account['role']): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg207-e2e-${randomBytes(8).toString('hex')}@example.com`;
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
  await expect(page).toHaveURL('/importer');
}

function assessment(page: Page) {
  return page.locator('.route-facts > dl > div').filter({ has: page.getByText('Deadline assessment', { exact: true }) });
}

function plannedArrival(page: Page) {
  return page.locator('.route-facts > dl > div').filter({ has: page.getByText('Planned arrival', { exact: true }) });
}

function localInput(instant: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Singapore',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(instant));
  const value = (type: string) => parts.find(part => part.type === type)?.value ?? '';
  return `${value('year')}-${value('month')}-${value('day')}T${value('hour')}:${value('minute')}:${value('second')}`;
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
      DELETE FROM vessel_observations
      WHERE vessel_name IN (SELECT mother_vessel FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email'));
      DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('an importer sees the server assessment and its replacement after a deadline change', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The deadline assessment is covered once on desktop.');
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const importer = await register(request, 'STRAITS_FRESH_DEMO', 'IMPORTER');
  const vessel = `MV CDG207 ${randomBytes(4).toString('hex')}`;
  try {
    const created = await request.post('/api/shipments', {
      headers: { Authorization: `Bearer ${forwarder.token}` },
      data: {
        shipmentReference: `CDG207-${randomBytes(4).toString('hex')}`,
        origin: 'Shanghai',
        destination: 'Jakarta',
        transshipmentPort: 'Singapore',
        motherVessel: vessel,
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
    sql(`INSERT INTO vessel_observations (mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
      VALUES ('563207001', :'vessel', 1.26, 103.82, 12.5, NOW(), 'SEEDED');`, { vessel });

    const before = await request.get(`/api/shipments/${shipmentId}`, { headers: { Authorization: `Bearer ${importer.token}` } });
    expect(before.status(), await before.text()).toBe(200);
    const initial = await before.json() as Detail;
    expect(initial.arrivalComparison.unavailableReason).toBeNull();
    expect(initial.delayComparison.reason).toBe('MISSING_CONSTRAINT');
    expect(initial.delayComparison.outcome).toBeNull();
    expect(initial.delayComparison.estimatedArrivalAt).toBeTruthy();
    const estimate = initial.delayComparison.estimatedArrivalAt as string;

    await signIn(page, importer);
    await page.goto(`/importer/shipments/${shipmentId}`);
    await expect(assessment(page).getByText(MISSING_CONSTRAINT)).toBeVisible();
    await expect(assessment(page).getByText('Not recorded').first()).toBeVisible();
    await expect(assessment(page).locator('time').first()).toHaveAttribute('datetime', estimate);
    await expect(assessment(page).getByText(NO_IMPACT)).toHaveCount(0);
    await expect(plannedArrival(page).getByText('Comparison', { exact: true })).toBeVisible();
    await expect(page.getByText('Risk', { exact: true })).toBeVisible();

    const later = new Date(new Date(estimate).getTime() + 2 * 60 * 60 * 1000).toISOString();
    await page.getByLabel('Deadline').fill(localInput(later));
    const savedResponse = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/transshipment-arrival-deadline') && response.ok());
    await page.getByRole('button', { name: 'Save deadline' }).click();
    const saved = await (await savedResponse).json() as Detail;
    expect(saved.delayComparison.outcome).toBe('NO_PREDICTED_IMPACT');
    expect(saved.delayComparison.message).toBe(NO_IMPACT);
    await expect(assessment(page).getByText(NO_IMPACT)).toBeVisible();
    await expect(assessment(page).getByText(MISSING_CONSTRAINT)).toHaveCount(0);
    await expect(assessment(page).locator('time').nth(1)).toHaveAttribute('datetime', saved.delayComparison.deadline ?? '');
    await expect(assessment(page).getByText('Singapore').first()).toBeVisible();

    const earlier = new Date(new Date(estimate).getTime() - 2 * 60 * 60 * 1000).toISOString();
    await page.getByLabel('Deadline').fill(localInput(earlier));
    const updatedResponse = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/transshipment-arrival-deadline') && response.ok());
    await page.getByRole('button', { name: 'Update deadline' }).click();
    const updated = await (await updatedResponse).json() as Detail;
    expect(updated.delayComparison.outcome).toBe('EXCEEDS_DEADLINE');
    expect(updated.delayComparison.message).toBe(EXCEEDS);
    await expect(assessment(page).getByText(EXCEEDS)).toBeVisible();
    await expect(assessment(page).getByText(NO_IMPACT)).toHaveCount(0);
    await expect(assessment(page).locator('time').nth(1)).toHaveAttribute('datetime', updated.delayComparison.deadline ?? '');
    await expect(plannedArrival(page).getByText('Comparison', { exact: true })).toBeVisible();
    expect(updated.arrivalComparison.difference).toBe(initial.arrivalComparison.difference);
  } finally {
    cleanup([forwarder.email, importer.email]);
  }
});
