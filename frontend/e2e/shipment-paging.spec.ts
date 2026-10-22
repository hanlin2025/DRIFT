import { test, expect, type APIRequestContext, type Page, type Response } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const host = process.env.DRIFT_E2E_DB_HOST ?? '';
const port = process.env.DRIFT_E2E_DB_PORT ?? '';
const dbUser = process.env.DRIFT_E2E_DB_USER ?? '';
const dbPassword = process.env.DRIFT_E2E_DB_PASSWORD ?? '';
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const access = process.env.DRIFT_E2E_PSQL ?? '';
const composeProject = process.env.DRIFT_E2E_COMPOSE_PROJECT ?? '';
const password = 'Example123';
const sharedVessel = 'Paging Vessel';
const otherVessel = 'Other Vessel';
const count = 26;

type Fixture = { index: number; reference: string; arrival: string; created: string; id?: number };
type Account = { email: string; id: number; companyId: number };

function assertRunContext() {
  if (host !== '127.0.0.1' || port !== '5432' || dbUser !== 'drift' || dbPassword !== 'drift') {
    throw new Error('Host, port, database user, and password must be set explicitly.');
  }
  if (access === 'compose' && !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(composeProject)) {
    throw new Error('The paging runner must pass the selected Compose project.');
  }
  if (access === 'host' && composeProject !== '') {
    throw new Error('A host psql connection must not also name a Compose project.');
  }
  if (access !== 'compose' && access !== 'host') {
    throw new Error('Run this spec through npm run test:e2e:paging.');
  }
  if (!/^drift_cdg171_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:paging.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  const serverPort = sql('SELECT inet_server_port()::text;');
  if (current !== database || comment !== ownerToken || serverPort !== '5432') {
    throw new Error('This database has no matching ownership record for this run.');
  }
}

function sql(query: string, variables: Record<string, string> = {}) {
  const variableArgs = Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`]);
  const common = ['-h', host, '-p', port, '-U', dbUser, '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', ...variableArgs];
  const env = { ...process.env, PGPASSWORD: dbPassword };
  if (access === 'compose') {
    return execFileSync('docker', ['compose', '-p', composeProject, 'exec', '-T', 'postgres', 'psql', ...common], {
      cwd: root, encoding: 'utf8', input: query, env,
    }).trim();
  }
  return execFileSync('psql', common, { encoding: 'utf8', input: query, env }).trim();
}

function stamp(year: number, month: number, day: number, hours: number) {
  return new Date(Date.UTC(year, month, day, hours, 0, 0)).toISOString();
}

const fixtures: Fixture[] = Array.from({ length: count }, (_, index) => ({
  index,
  reference: `CDG171-R${String(index).padStart(2, '0')}`,
  arrival: stamp(2026, 11, 1, count - 1 - index),
  created: stamp(2026, 0, 1, (index * 7) % count),
}));

function ordered(field: 'reference' | 'arrival' | 'created', direction: 'asc' | 'desc') {
  return [...fixtures].sort((left, right) => {
    const compared = left[field].localeCompare(right[field]);
    if (compared !== 0) return direction === 'asc' ? compared : -compared;
    return left.index - right.index;
  });
}

function pageOf(rows: Fixture[], pageNumber: number) {
  return rows.slice((pageNumber - 1) * 25, pageNumber * 25);
}

function referencesOf(rows: Fixture[]) {
  return rows.map(row => row.reference);
}

function idsOf(rows: Fixture[]) {
  return rows.map(row => {
    if (row.id === undefined) throw new Error(`Missing id for ${row.reference}`);
    return row.id;
  });
}

function insertCompany(code: string, name: string) {
  sql('INSERT INTO companies (code, name, active) VALUES (:\'code\', :\'name\', TRUE);', { code, name });
}

async function register(request: APIRequestContext, company: string, fullName: string): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg171-${randomBytes(6).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName, email, password, invitationToken: invitation },
  });
  expect(registered.status(), await registered.text()).toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  const bodyText = await signedIn.text();
  expect(signedIn.status(), bodyText).toBe(200);
  const body = JSON.parse(bodyText);
  return { email, id: body.id, companyId: body.company.id };
}

function insertShipment(account: Account, reference: string, vessel: string, arrival: string, created: string) {
  if (!Number.isInteger(account.companyId) || !Number.isInteger(account.id)) throw new Error('Account ids must be integers.');
  const inserted = sql(`INSERT INTO shipments (
      company_id, created_by_user_id, updated_by_user_id,
      shipment_reference, origin, destination, transshipment_port, mother_vessel,
      planned_mother_arrival_at, feeder_vessel, planned_feeder_departure_at,
      created_at, updated_at, version)
    VALUES (
      ${account.companyId}, ${account.id}, ${account.id},
      :'reference', 'Singapore', 'Rotterdam', 'Tanjung Pelepas', :'vessel',
      :'arrival'::timestamptz, 'Feeder Vessel', :'arrival'::timestamptz + INTERVAL '1 hour',
      :'created'::timestamptz, :'created'::timestamptz, 0)
    RETURNING id;`, { reference, vessel, arrival, created });
  const id = inserted.split('\n').find(line => /^[0-9]+$/.test(line));
  if (!id) throw new Error(`Shipment insert did not return an id for ${reference}.`);
  return Number(id);
}

function cleanup(ownerEmail: string, otherEmail: string, ownerCode: string, otherCode: string) {
  sql(`DELETE FROM shipments WHERE company_id IN (SELECT id FROM companies WHERE code IN (:'owner', :'other'));
    DELETE FROM invitations WHERE email IN (:'ownerEmail', :'otherEmail');
    DELETE FROM users WHERE email IN (:'ownerEmail', :'otherEmail');
    DELETE FROM companies WHERE code IN (:'owner', :'other');`, {
    owner: ownerCode, other: otherCode, ownerEmail, otherEmail,
  });
}

function listQuery(response: Response, expected: Record<string, string | null>) {
  if (response.request().method() !== 'GET' || !response.ok()) return false;
  const url = new URL(response.url());
  if (url.pathname !== '/api/shipments') return false;
  return Object.entries(expected).every(([key, value]) => (
    value === null ? !url.searchParams.has(key) : url.searchParams.get(key) === value
  ));
}

async function listed(page: Page, expected: Record<string, string | null>) {
  const response = await page.waitForResponse(response => listQuery(response, expected));
  return response.json();
}

async function expectRows(page: Page, references: string[]) {
  await expect.poll(() => page.locator('tbody tr td:first-child').allInnerTexts()).toEqual(references);
  await expect(page.getByRole('link', { name: 'CDG171-HIDDEN' })).toHaveCount(0);
}

async function expectQuery(page: Page, expected: Record<string, string | null>) {
  await expect.poll(() => {
    const params = new URL(page.url()).searchParams;
    return Object.fromEntries(Object.keys(expected).map(key => [key, params.get(key)]));
  }).toEqual(expected);
}

async function signIn(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  const initial = listed(page, { page: '1', vessel: null, sort: null, direction: null });
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');
  return initial;
}

test('the portfolio pages and sorts persisted authorised shipments', { tag: '@disposable-database' }, async ({ page, request }) => {
  test.setTimeout(120_000);
  assertRunContext();
  const suffix = randomBytes(4).toString('hex');
  const ownerCode = `CDG171O${suffix}`.toUpperCase();
  const otherCode = `CDG171X${suffix}`.toUpperCase();
  let ownerEmail = '';
  let otherEmail = '';
  insertCompany(ownerCode, `CDG171 owner ${suffix}`);
  insertCompany(otherCode, `CDG171 other ${suffix}`);
  try {
    const owner = await register(request, ownerCode, 'Paging Owner');
    ownerEmail = owner.email;
    const other = await register(request, otherCode, 'Paging Other');
    otherEmail = other.email;
    for (const fixture of fixtures) {
      fixture.id = insertShipment(owner, fixture.reference, sharedVessel, fixture.arrival, fixture.created);
    }
    const otherId = insertShipment(owner, 'CDG171-OTHER', otherVessel, stamp(2026, 9, 1, 0), stamp(2026, 4, 1, 0));
    const hiddenId = insertShipment(other, 'CDG171-HIDDEN', sharedVessel, stamp(2026, 7, 1, 0), stamp(2026, 5, 1, 0));
    expect(sql(`SELECT COUNT(*) FROM shipments WHERE mother_vessel = :'vessel';`, { vessel: sharedVessel })).toBe('27');

    const createdDescending = ordered('created', 'desc');
    const unfiltered = await signIn(page, owner.email);
    expect(unfiltered).toMatchObject({ page: 1, size: 25, total: 27 });
    expect(unfiltered.items.map((item: { id: number }) => item.id)).toContain(otherId);
    expect(unfiltered.items.map((item: { id: number }) => item.id)).not.toContain(hiddenId);
    await expect(page.getByRole('navigation', { name: 'Shipment pages' })).toContainText('1–25 of 27');
    await expect(page.locator('tbody tr td:first-child').first()).toHaveText('CDG171-OTHER');
    await expect(page.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Next' })).toBeEnabled();

    await page.getByLabel('Filter by vessel').fill(sharedVessel);
    const filtered = listed(page, { page: '1', vessel: sharedVessel, sort: null, direction: null });
    await page.getByRole('button', { name: 'Apply filters' }).click();
    const filteredBody = await filtered;
    expect(filteredBody).toMatchObject({ page: 1, size: 25, total: 26 });
    expect(filteredBody.items.map((item: { shipmentReference: string }) => item.shipmentReference)).toEqual(referencesOf(pageOf(createdDescending, 1)));
    expect(filteredBody.items.map((item: { id: number }) => item.id)).toEqual(idsOf(pageOf(createdDescending, 1)));
    await expectQuery(page, { page: null, vessel: sharedVessel, sort: null, direction: null });
    await expect(page.getByRole('navigation', { name: 'Shipment pages' })).toContainText('1–25 of 26');
    await expectRows(page, referencesOf(pageOf(createdDescending, 1)));
    await expect(page.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Next' })).toBeEnabled();

    async function chooseSort(control: 'Sort shipments' | 'Sort direction', value: string, field: 'reference' | 'arrival' | 'created', direction: 'asc' | 'desc', query: { sort: string | null; direction: string | null }) {
      const response = listed(page, { page: '1', vessel: sharedVessel, sort: query.sort, direction: query.direction });
      await page.getByLabel(control).selectOption(value);
      const body = await response;
      const rows = pageOf(ordered(field, direction), 1);
      expect(body.items.map((item: { id: number }) => item.id)).toEqual(idsOf(rows));
      expect(body.items.map((item: { shipmentReference: string }) => item.shipmentReference)).toEqual(referencesOf(rows));
      expect(body).toMatchObject({ page: 1, size: 25, total: 26 });
      expect(body.items.map((item: { id: number }) => item.id)).not.toContain(hiddenId);
      await expectQuery(page, { page: null, vessel: sharedVessel, ...query });
      await expectRows(page, referencesOf(rows));
    }

    await chooseSort('Sort direction', 'asc', 'created', 'asc', { sort: null, direction: 'asc' });
    await chooseSort('Sort direction', 'desc', 'created', 'desc', { sort: null, direction: null });
    await chooseSort('Sort shipments', 'plannedArrival', 'arrival', 'desc', { sort: 'plannedArrival', direction: null });
    await chooseSort('Sort direction', 'asc', 'arrival', 'asc', { sort: 'plannedArrival', direction: 'asc' });
    await chooseSort('Sort direction', 'desc', 'arrival', 'desc', { sort: 'plannedArrival', direction: null });
    await chooseSort('Sort shipments', 'reference', 'reference', 'desc', { sort: 'reference', direction: null });
    await chooseSort('Sort direction', 'asc', 'reference', 'asc', { sort: 'reference', direction: 'asc' });

    const referenceAscending = ordered('reference', 'asc');
    const secondPage = listed(page, { page: '2', vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await page.getByRole('button', { name: 'Next' }).click();
    const secondBody = await secondPage;
    expect(secondBody).toMatchObject({ page: 2, size: 25, total: 26 });
    expect(secondBody.items.map((item: { id: number }) => item.id)).toEqual(idsOf(pageOf(referenceAscending, 2)));
    await expectQuery(page, { page: '2', vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await expect(page.getByRole('navigation', { name: 'Shipment pages' })).toContainText('26–26 of 26');
    await expectRows(page, ['CDG171-R25']);
    await expect(page.getByRole('button', { name: 'Previous' })).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled();

    const reloaded = listed(page, { page: '2', vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await page.reload();
    expect((await reloaded).items.map((item: { shipmentReference: string }) => item.shipmentReference)).toEqual(['CDG171-R25']);
    await expectQuery(page, { page: '2', vessel: sharedVessel, sort: 'reference', direction: 'asc' });

    const backToFirst = listed(page, { page: '1', vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await page.goBack();
    expect((await backToFirst).items[0].shipmentReference).toBe('CDG171-R00');
    await expectQuery(page, { page: null, vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    const forwardAgain = listed(page, { page: '2', vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await page.goForward();
    expect((await forwardAgain).items.map((item: { shipmentReference: string }) => item.shipmentReference)).toEqual(['CDG171-R25']);
    await expectQuery(page, { page: '2', vessel: sharedVessel, sort: 'reference', direction: 'asc' });

    const sortResetsPage = listed(page, { page: '1', vessel: sharedVessel, sort: null, direction: 'asc' });
    await page.getByLabel('Sort shipments').selectOption('createdAt');
    const createdAscending = ordered('created', 'asc');
    expect((await sortResetsPage).items.map((item: { id: number }) => item.id)).toEqual(idsOf(pageOf(createdAscending, 1)));
    await expectQuery(page, { page: null, vessel: sharedVessel, sort: null, direction: 'asc' });
    await expectRows(page, referencesOf(pageOf(createdAscending, 1)));

    const toSecond = listed(page, { page: '2', vessel: sharedVessel, sort: null, direction: 'asc' });
    await page.getByRole('button', { name: 'Next' }).click();
    await toSecond;
    await page.getByLabel('Filter by vessel').fill(otherVessel);
    const filterResetsPage = listed(page, { page: '1', vessel: otherVessel, sort: null, direction: 'asc' });
    await page.getByRole('button', { name: 'Apply filters' }).click();
    const otherBody = await filterResetsPage;
    expect(otherBody).toMatchObject({ page: 1, size: 25, total: 1 });
    expect(otherBody.items.map((item: { id: number }) => item.id)).toEqual([otherId]);
    await expectQuery(page, { page: null, vessel: otherVessel, sort: null, direction: 'asc' });
    await expectRows(page, ['CDG171-OTHER']);
    await expect(page.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled();

    await page.getByLabel('Filter by vessel').fill(sharedVessel);
    const restoredFilter = listed(page, { page: '1', vessel: sharedVessel, sort: null, direction: 'asc' });
    await page.getByRole('button', { name: 'Apply filters' }).click();
    expect((await restoredFilter).total).toBe(26);
    await page.getByLabel('Sort shipments').selectOption('reference');
    await expectQuery(page, { page: null, vessel: sharedVessel, sort: 'reference', direction: 'asc' });

    const outside = listed(page, { page: '9', vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await page.goto('/freight-forwarder?page=9&vessel=Paging%20Vessel&sort=reference&direction=asc');
    const outsideBody = await outside;
    expect(outsideBody).toMatchObject({ page: 9, size: 25, total: 26, items: [] });
    await expect(page.getByText('No shipments on this page.')).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Shipment pages' })).toContainText('0 of 26');
    await expect(page.getByRole('button', { name: 'Previous' })).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled();
    await expectQuery(page, { page: '9', vessel: sharedVessel, sort: 'reference', direction: 'asc' });

    const returned = listed(page, { page: '1', vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await page.getByRole('button', { name: 'Back to the first page' }).click();
    expect((await returned).items.map((item: { shipmentReference: string }) => item.shipmentReference).slice(0, 2)).toEqual(['CDG171-R00', 'CDG171-R01']);
    await expectQuery(page, { page: null, vessel: sharedVessel, sort: 'reference', direction: 'asc' });
    await expect(page.getByRole('navigation', { name: 'Shipment pages' })).toContainText('1–25 of 26');
    await expect(page.getByRole('button', { name: 'Previous' })).toBeDisabled();
  } finally {
    if (ownerEmail && otherEmail) cleanup(ownerEmail, otherEmail, ownerCode, otherCode);
  }
});
