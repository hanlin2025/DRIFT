import { test, expect, request as playwrightRequest, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const header = 'Tracking/BL No.,Origin Port,Destination Port,Transshipment Port,Mother Vessel,Planned Mother Arrival,Feeder Vessel,Planned Feeder Departure,Importer Organisation';

type Account = { email: string; token: string };

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg130_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:import. A database name is not proof that this run created the database.');
  }
  if (sql('SELECT current_database();') !== database || sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();") !== ownerToken) {
    throw new Error('This database has no matching ownership record from the shipment import runner. Refusing to write fixtures.');
  }
}

const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function sql(query: string, variables: Record<string, string> = {}) {
  const variableArgs = Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`]);
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', ...variableArgs];
  const invocation = useDocker
    ? { command: 'docker', args: ['compose', 'exec', '-T', 'postgres', 'psql', ...common], env: process.env }
    : { command: 'psql', args: ['-h', '127.0.0.1', ...common], env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' } };
  return execFileSync(invocation.command, invocation.args, { cwd: root, input: query, encoding: 'utf8', env: invocation.env }).trim();
}

async function registerAccount(request: APIRequestContext, role: 'FREIGHT_FORWARDER' | 'IMPORTER', company: string): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg130-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour' FROM companies WHERE code = :'company';`,
  { email, role, hash: createHash('sha256').update(invitation).digest('hex'), company });
  const registration = await request.post('/api/register', { data: { fullName: 'Alex Tan', email, password, invitationToken: invitation } });
  expect(registration.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  return { email, token: (await signedIn.json()).token as string };
}

function seedRelationship(forwarderEmail: string, importerCode: string) {
  sql(`INSERT INTO forwarder_importer_relationships
      (forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
    SELECT forwarder.company_id, importer.id, true, NOW(), forwarder.id, NOW(), forwarder.id
    FROM users forwarder JOIN companies importer ON importer.code = :'importer'
    WHERE forwarder.email = :'email';`, { email: forwarderEmail, importer: importerCode });
}

async function signIn(page: Page, email: string, path = '/freight-forwarder') {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(path);
}

function row(reference: string, importerCode = '', overrides: Partial<Record<'origin' | 'motherArrival' | 'feederDeparture', string>> = {}) {
  return [reference, overrides.origin ?? 'Singapore', 'Rotterdam', 'Tanjung Pelepas', 'MV Test Mother',
    overrides.motherArrival ?? '2026-10-10T09:30:00+08:00', 'MV Test Feeder',
    overrides.feederDeparture ?? '2026-10-10T13:30:00+08:00', importerCode].join(',');
}

async function importThroughPage(page: Page, contents: string, name: string) {
  const file = page.getByLabel('CSV file');
  await file.setInputFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(contents) });
  const accepted = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/shipments/import');
  await page.getByRole('button', { name: 'Import shipments' }).click();
  const response = await accepted;
  expect(response.status(), await response.text()).toBe(202);
  const job = await response.json() as { id: number };
  await expect(page).toHaveURL(new RegExp(`/freight-forwarder/import/${job.id}$`));
  return job.id;
}

function cleanup(emails: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM shipment_import_jobs WHERE requested_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM forwarder_importer_relationships WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email'; DELETE FROM users WHERE email = :'email';`, { email });
  }
}

test.beforeAll(() => assertOwnedDatabase());

test('a forwarder imports linked shipments through the real portfolio and linked importer access', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The import journey is covered once on desktop.');
  test.setTimeout(90_000);
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const importer = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO');
  seedRelationship(forwarder.email, 'STRAITS_FRESH_DEMO');
  const linkedReference = `HBL-CDG130-LINKED-${randomBytes(4).toString('hex')}`;
  const ordinaryReference = `HBL-CDG130-ORDINARY-${randomBytes(4).toString('hex')}`;
  try {
    await signIn(page, forwarder.email);
    await page.getByRole('link', { name: 'Import shipments' }).click();
    const template = page.waitForEvent('download');
    await page.getByRole('link', { name: 'Download CSV Template' }).click();
    expect((await template).suggestedFilename()).toBe('shipment-import-template.csv');
    const jobId = await importThroughPage(page, `${header}\n${row(linkedReference, 'STRAITS_FRESH_DEMO')}\n${row(ordinaryReference)}`, 'portfolio.csv');
    await expect(page.getByRole('status')).toHaveText('2 shipments imported successfully.', { timeout: 45_000 });
    expect(sql('SELECT status FROM shipment_import_jobs WHERE id = :\'job\';', { job: String(jobId) })).toBe('COMPLETED');
    expect(sql('SELECT importer_company_id FROM shipments WHERE shipment_reference = :\'reference\';', { reference: linkedReference })).toBe(sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO';"));
    expect(sql('SELECT created_by_user_id = updated_by_user_id FROM shipments WHERE shipment_reference = :\'reference\';', { reference: linkedReference })).toBe('t');

	    await page.getByRole('link', { name: 'Back to Shipments', exact: true }).click();
    await expect(page.getByRole('link', { name: linkedReference })).toBeVisible();
    await page.getByRole('link', { name: linkedReference }).click();
    await expect(page.getByRole('heading', { name: linkedReference })).toBeVisible();

    const importerApi = await playwrightRequest.newContext({ baseURL: 'http://127.0.0.1:5173', extraHTTPHeaders: { Authorization: `Bearer ${importer.token}` } });
    try {
      const list = await importerApi.get('/api/shipments');
      expect(list.status()).toBe(200);
      expect(await list.json()).toEqual(expect.arrayContaining([expect.objectContaining({ shipmentReference: linkedReference })]));
      const shipmentId = Number(sql('SELECT id FROM shipments WHERE shipment_reference = :\'reference\';', { reference: linkedReference }));
      expect((await importerApi.get(`/api/shipments/${shipmentId}`)).status()).toBe(200);
      expect((await importerApi.get(`/api/shipments/${shipmentId}/tracking`)).status()).toBe(200);
      const update = await importerApi.put(`/api/shipments/${shipmentId}`, { data: { shipmentReference: linkedReference, origin: 'Singapore', destination: 'Rotterdam', transshipmentPort: 'Tanjung Pelepas', motherVessel: 'MV Test Mother', plannedMotherArrivalAt: '2026-10-10T09:30:00+08:00', feederVessel: 'MV Test Feeder', plannedFeederDepartureAt: '2026-10-10T13:30:00+08:00', version: 0 } });
      expect(update.status()).toBe(403);
    } finally { await importerApi.dispose(); }
  } finally { cleanup([forwarder.email, importer.email]); }
});

test('a partial import displays API row errors and downloads the real error report', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The import journey is covered once on desktop.');
  test.setTimeout(90_000);
  const forwarder = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const validReference = `HBL-CDG130-PARTIAL-${randomBytes(4).toString('hex')}`;
  try {
    await signIn(page, forwarder.email);
    await page.getByRole('link', { name: 'Import shipments' }).click();
    const jobId = await importThroughPage(page, `${header}\n${row(validReference)}\n${row('', '', { origin: '' , motherArrival: 'not-a-time', feederDeparture: '2026-10-10T08:00:00+08:00'})}`, 'partial.csv');
    await expect(page.getByRole('status')).toHaveText('1 imported, 1 failed.', { timeout: 45_000 });
    await expect(page.getByRole('heading', { name: 'Rows needing attention' })).toBeVisible();
    await expect(page.locator('.import-errors tbody')).toContainText('Tracking/BL No. is required');
    await expect(page.locator('.import-errors tbody')).toContainText('Planned Mother Arrival must include a timezone offset');
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download Error Report' }).click();
    const report = await download;
    const contents = await report.createReadStream().then(async stream => {
      const chunks: Buffer[] = []; for await (const chunk of stream) chunks.push(Buffer.from(chunk)); return Buffer.concat(chunks).toString('utf8');
    });
    expect(report.suggestedFilename()).toBe(`shipment-import-errors-${jobId}.csv`);
    expect(contents).toContain('Row,Column,Error Code,Reason\r\n');
    expect(contents).toContain('3,Tracking/BL No.,REQUIRED,Tracking/BL No. is required\r\n');
    expect(contents).toContain('3,Planned Mother Arrival,INVALID_TIMESTAMP,Planned Mother Arrival must include a timezone offset\r\n');
    expect(sql('SELECT imported_count || \':\' || failed_count || \':\' || processed_rows || \':\' || total_rows FROM shipment_import_jobs WHERE id = :\'job\';', { job: String(jobId) })).toBe('1:1:2:2');
    expect(sql('SELECT COUNT(*) FROM shipments WHERE shipment_reference = :\'reference\';', { reference: validReference })).toBe('1');
  } finally { cleanup([forwarder.email]); }
});
