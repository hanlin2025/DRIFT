import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const access = process.env.DRIFT_E2E_PSQL ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const HOUR = 60 * 60 * 1000;
const NO_ACCESS = 'You do not have access to this shipment';
const ESTIMATED = 'The estimated arrival was used.';
const LIMITED = 'Confidence is limited because the planned connection window is being used.';
const MISSING_FEEDER = 'The assessment is unavailable because the planned feeder-vessel departure is missing.';
const REMAINING_WINDOW = 'Remaining connection window';
const PLANNED_WINDOW = 'Planned connection window';
const RISK_LABELS = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' } as const;

type Account = { email: string; token: string; role: 'FREIGHT_FORWARDER' | 'IMPORTER' };
type RiskLevel = keyof typeof RISK_LABELS;

type Explanation = {
  evidence: string[] | null;
  assumptions: string[] | null;
  confidence: string | null;
  unavailable: string[] | null;
};

type Detail = {
  id: number;
  version: number;
  shipmentReference: string;
  transshipmentPort: string;
  motherVessel: string;
  feederVessel: string;
  risk: { level: RiskLevel; explanation: string } | null;
  arrivalComparison: { estimatedArrivalAt: string | null };
  assessmentExplanation: Explanation;
};

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg226_browser_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:assessment-explanation. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the assessment explanation runner. Refusing to write fixtures.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  if (access !== 'compose' && access !== 'host') {
    throw new Error('Run this spec through npm run test:e2e:assessment-explanation. The runner chooses the database access path.');
  }
  const common = ['-h', '127.0.0.1', '-p', '5432', '-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  if (access === 'compose') {
    return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common],
      { cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' } }).trim();
  }
  return execFileSync('psql', common, {
    cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  }).trim();
}

function mmsi() {
  return `6${randomBytes(4).readUInt32BE(0).toString().padStart(8, '0').slice(0, 8)}`;
}

function vessel() {
  return `EXPLAIN ${randomBytes(4).toString('hex').toUpperCase()}`;
}

async function register(request: APIRequestContext, companyCode: string, role: Account['role']): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg226-e2e-${randomBytes(8).toString('hex')}@example.com`;
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

function seed(name: string, ingestedAt: string) {
  sql(`INSERT INTO vessel_observations (
      mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
    VALUES (:'mmsi', :'vessel', 1.26, 103.82, '12', :'ingested', 'SEEDED');`,
    { mmsi: mmsi(), vessel: name, ingested: ingestedAt });
}

function seedRelationship(forwarderEmail: string, importerCode: string) {
  sql(`INSERT INTO forwarder_importer_relationships
      (forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
    SELECT forwarder.company_id, importer.id, true, NOW(), forwarder.id, NOW(), forwarder.id
    FROM users forwarder JOIN companies importer ON importer.code = :'importer'
    WHERE forwarder.email = :'email';`, { email: forwarderEmail, importer: importerCode });
}

async function createShipment(request: APIRequestContext, token: string, name: string, planned: string, departure: string) {
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference: `HBL-CDG226-${randomBytes(4).toString('hex')}`,
      origin: 'Jakarta',
      destination: 'Rotterdam',
      transshipmentPort: 'Singapore',
      motherVessel: name,
      plannedMotherArrivalAt: planned,
      feederVessel: 'Feeder',
      plannedFeederDepartureAt: departure,
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const created = await response.json() as { id: number };
  return read(request, token, created.id);
}

async function read(request: APIRequestContext, token: string, id: number) {
  const detail = await request.get(`/api/shipments/${id}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(detail.status(), await detail.text()).toBe(200);
  return await detail.json() as Detail;
}

async function placeFeeder(request: APIRequestContext, token: string, detail: Detail, plannedOffsetMs: number, feederOffsetMs: number) {
  const eta = Date.parse(detail.arrivalComparison.estimatedArrivalAt ?? '');
  expect(Number.isFinite(eta)).toBe(true);
  const updated = await request.put(`/api/shipments/${detail.id}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference: detail.shipmentReference,
      origin: 'Jakarta',
      destination: 'Rotterdam',
      transshipmentPort: detail.transshipmentPort,
      motherVessel: detail.motherVessel,
      plannedMotherArrivalAt: new Date(eta + plannedOffsetMs).toISOString(),
      feederVessel: detail.feederVessel,
      plannedFeederDepartureAt: new Date(eta + feederOffsetMs).toISOString(),
      version: detail.version,
    },
  });
  expect(updated.status(), await updated.text()).toBe(200);
  return read(request, token, detail.id);
}

function forgetFeederDeparture(id: number) {
  const names = sql(`SELECT conname FROM pg_constraint
    WHERE conrelid = 'public.shipments'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%planned_feeder_departure_at%';`);
  for (const name of names.split('\n').map(item => item.trim()).filter(Boolean)) {
    if (!/^[a-z0-9_]+$/.test(name)) throw new Error(`Unexpected constraint ${name}`);
    sql(`ALTER TABLE shipments DROP CONSTRAINT ${name};`);
  }
  sql('ALTER TABLE shipments ALTER COLUMN planned_feeder_departure_at DROP NOT NULL;');
  sql('UPDATE shipments SET planned_feeder_departure_at = NULL WHERE id = :\'id\';', { id: String(id) });
}

function fresh(hoursAgo = 0) {
  return new Date(Date.now() - hoursAgo * HOUR).toISOString();
}

async function signIn(page: Page, account: Account) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(account.role === 'IMPORTER' ? '/importer' : '/freight-forwarder');
}

function fact(page: Page, label: string) {
  return page.locator('.route-facts > dl > div').filter({ has: page.getByText(label, { exact: true }) }).locator('dd').first();
}

async function openShipment(page: Page, account: Account, id: number) {
  await signIn(page, account);
  await page.goto(`${account.role === 'IMPORTER' ? '/importer' : '/freight-forwarder'}/shipments/${id}`);
}

async function expectSentences(page: Page, label: string, sentences: string[]) {
  const value = fact(page, label);
  for (const sentence of sentences) await expect(value).toContainText(sentence);
}

async function expectCompleted(page: Page, detail: Detail) {
  const explanation = detail.assessmentExplanation;
  expect(explanation.evidence).toEqual(expect.any(Array));
  expect(explanation.assumptions).toEqual(expect.any(Array));
  await expectSentences(page, 'Evidence', explanation.evidence ?? []);
  await expectSentences(page, 'Assumptions', explanation.assumptions ?? []);
  await expect(fact(page, 'Confidence')).toContainText(explanation.confidence ?? '');
  await expect(page.getByText('Unavailable', { exact: true })).toHaveCount(0);
  if (!detail.risk) throw new Error('A completed assessment keeps the risk fact');
  await expect(fact(page, 'Risk').locator('.risk-badge')).toHaveText(RISK_LABELS[detail.risk.level]);
  await expect(fact(page, 'Risk')).toContainText(detail.risk.explanation);
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('a usable remaining window shows the evidence, assumptions, confidence, and the existing risk', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The shipment page is covered once on desktop.');
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const name = vessel();
  seed(name, fresh());
  const created = await createShipment(request, forwarder.token, name, fresh(-1), fresh(-2));
  const detail = await placeFeeder(request, forwarder.token, created, -2 * HOUR, 6 * HOUR);

  expect(detail.assessmentExplanation.confidence).toBe(ESTIMATED);
  expect(detail.assessmentExplanation.unavailable).toBeNull();
  expect(detail.assessmentExplanation.evidence?.[0]).toBe(REMAINING_WINDOW);
  expect(detail.assessmentExplanation.evidence?.some(item => item.startsWith('Position received ') && item.includes('Speed used '))).toBe(true);
  expect(detail.risk?.explanation).toContain("mother vessel's estimated arrival");

  await openShipment(page, forwarder, detail.id);
  await expectCompleted(page, detail);
  await expect(page.getByText(LIMITED)).toHaveCount(0);
});

test('a completed limited confidence shows that confidence with the evidence and assumptions', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The shipment page is covered once on desktop.');
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const detail = await createShipment(request, forwarder.token, vessel(), fresh(-1), fresh(-7));

  expect(detail.assessmentExplanation.confidence).toBe(LIMITED);
  expect(detail.assessmentExplanation.unavailable).toBeNull();
  expect(detail.assessmentExplanation.evidence?.[0]).toBe(PLANNED_WINDOW);
  expect(detail.assessmentExplanation.evidence).toContain('No AIS position has been received for the mother vessel.');
  expect(detail.risk).not.toBeNull();

  await openShipment(page, forwarder, detail.id);
  await expectCompleted(page, detail);
  await expect(page.getByText('Unavailable', { exact: true })).toHaveCount(0);
});

test('a shipment with no planned feeder departure names that reason and does not show limited confidence', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The shipment page is covered once on desktop.');
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const created = await createShipment(request, forwarder.token, vessel(), fresh(-1), fresh(-7));
  forgetFeederDeparture(created.id);
  const detail = await read(request, forwarder.token, created.id);

  expect(detail.assessmentExplanation).toEqual({
    evidence: null,
    assumptions: null,
    confidence: null,
    unavailable: [MISSING_FEEDER],
  });
  expect(detail.risk).toBeNull();

  await openShipment(page, forwarder, detail.id);
  await expect(fact(page, 'Unavailable')).toContainText(MISSING_FEEDER);
  await expect(fact(page, 'Risk')).toHaveText('Pending assignment');
  await expect(page.getByText(LIMITED)).toHaveCount(0);
  await expect(page.getByText('Evidence', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Assumptions', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Confidence', { exact: true })).toHaveCount(0);
});

test('a linked importer sees the same evidence, assumptions, and confidence as the owning company', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The shipment page is covered once on desktop.');
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const importer = await register(request, 'STRAITS_FRESH_DEMO', 'IMPORTER');
  const name = vessel();
  seed(name, fresh());
  const created = await createShipment(request, forwarder.token, name, fresh(-1), fresh(-2));
  const detail = await placeFeeder(request, forwarder.token, created, -2 * HOUR, 6 * HOUR);
  seedRelationship(forwarder.email, 'STRAITS_FRESH_DEMO');
  const importerCompanyId = sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'");
  const linked = await request.patch(`/api/shipments/${detail.id}/link-importer`, {
    headers: { Authorization: `Bearer ${forwarder.token}` },
    data: { importerCompanyId: Number(importerCompanyId) },
  });
  expect(linked.status(), await linked.text()).toBe(200);

  const seen = await read(request, importer.token, detail.id);
  expect(seen.assessmentExplanation).toEqual(detail.assessmentExplanation);
  expect(seen.risk).toEqual(detail.risk);

  await openShipment(page, importer, detail.id);
  await expect(page.getByRole('heading', { name: detail.shipmentReference })).toBeVisible();
  await expectCompleted(page, seen);
});

test('another company receives 403 and the access denied page shows none of the explanation', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The shipment page is covered once on desktop.');
  const forwarder = await register(request, 'HARBOURLINE_DEMO', 'FREIGHT_FORWARDER');
  const outsider = await register(request, 'STRAITS_FRESH_DEMO', 'FREIGHT_FORWARDER');
  const detail = await createShipment(request, forwarder.token, vessel(), fresh(-1), fresh(-7));

  const denied = await request.get(`/api/shipments/${detail.id}`, { headers: { Authorization: `Bearer ${outsider.token}` } });
  expect(denied.status()).toBe(403);
  expect(await denied.json()).toEqual({ message: NO_ACCESS });

  await openShipment(page, outsider, detail.id);
  await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
  await expect(page.getByText(NO_ACCESS)).toBeVisible();
  await expect(page.getByText(detail.shipmentReference)).toHaveCount(0);
  await expect(page.getByText('Evidence', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Assumptions', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Confidence', { exact: true })).toHaveCount(0);
  await expect(page.getByText(LIMITED)).toHaveCount(0);
  await expect(page.getByText('Unavailable', { exact: true })).toHaveCount(0);
});
