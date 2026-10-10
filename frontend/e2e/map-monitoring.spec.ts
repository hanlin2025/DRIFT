import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const LAST_KNOWN = 'Last known position - data may be outdated';
const NO_POSITION = 'No live AIS position for this shipment.';
const NO_ACCESS = 'You do not have access to this shipment';
const NOT_FOUND = 'Shipment not found';
const SESSION_ENDED = 'Your session has ended. Log in again.';

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg106_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:map. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the map monitoring runner. Refusing to write fixtures.');
  }
}

const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function psqlInvocation(extra: string[]) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', ...extra];
  if (useDocker) {
    return { command: 'docker', args: ['compose', 'exec', '-T', 'postgres', 'psql', ...common], env: process.env };
  }
  return {
    command: 'psql',
    args: ['-h', '127.0.0.1', ...common],
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  };
}

function sql(query: string, variables: Record<string, string> = {}) {
  const variableArgs = Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`]);
  const invocation = psqlInvocation(variableArgs);
  return execFileSync(invocation.command, invocation.args, {
    cwd: root, input: query, encoding: 'utf8', env: invocation.env,
  }).trim();
}

function mmsi() {
  return String(100000000 + Math.floor(Math.random() * 899999999));
}

type Account = { email: string; token: string };

async function registerAccount(request: APIRequestContext, role: 'FREIGHT_FORWARDER' | 'IMPORTER', company: string): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg106-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName: 'Alex Tan', email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  const body = await signedIn.json();
  expect(body.token).toEqual(expect.any(String));
  return { email, token: body.token };
}

async function createShipment(request: APIRequestContext, token: string, motherVessel: string) {
  const shipmentReference = `CDG106-${randomBytes(4).toString('hex')}`;
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      shipmentReference,
      origin: 'Rotterdam',
      destination: 'Jakarta',
      transshipmentPort: 'Singapore',
      motherVessel,
      plannedMotherArrivalAt: '2026-10-15T08:00:00+08:00',
      feederVessel: 'Straits Feeder',
      plannedFeederDepartureAt: '2026-10-16T12:00:00+08:00',
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const body = await response.json();
  expect(body.id).toEqual(expect.any(Number));
  return { id: body.id as number, shipmentReference, motherVessel };
}

function seedObservation(vessel: string, identifier: string, stale: boolean) {
  const ingested = stale ? "NOW() - INTERVAL '7 hours'" : 'NOW()';
  sql(`INSERT INTO vessel_observations (
      mmsi, vessel_name, latitude, longitude, speed_over_ground_knots,
      course_over_ground_degrees, true_heading_degrees, navigational_status,
      position_valid, ais_utc_second, ingested_at, source
    ) VALUES (:'mmsi', :'vessel', 1.264, 103.820, 12.4, 175, 176, 0, true, 12, ${ingested}, 'SEEDED');`,
    { mmsi: identifier, vessel });
}

function cleanup(emails: string[], identifiers: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
  for (const identifier of identifiers) {
    sql("DELETE FROM vessel_observations WHERE mmsi = :'mmsi';", { mmsi: identifier });
  }
}

async function signIn(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL('/freight-forwarder');
}

async function openMap(page: Page, id: number) {
  const tracking = page.waitForResponse(response =>
    response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/shipments/${id}/tracking`);
  await page.goto(`/freight-forwarder/shipments/${id}`);
  expect((await tracking).status()).toBe(200);
  await expect(page.locator('.globe-pin.live.mother .globe-dot')).toBeVisible({ timeout: 30_000 });
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('an authorised freight forwarder sees the live vessel marker', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The map is covered once on desktop.');
  test.setTimeout(90_000);
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const vessel = `Ever Steady ${randomBytes(3).toString('hex')}`;
  const identifier = mmsi();
  const shipment = await createShipment(request, owner.token, vessel);
  seedObservation(vessel, identifier, false);
  try {
    await signIn(page, owner.email);
    await openMap(page, shipment.id);
    const marker = page.locator('.globe-pin.live.mother');
    await expect(marker).toHaveAttribute('title', new RegExp(`MOTHER LIVE ${vessel}\\. Last updated`));
    await expect(page.locator('.route-live')).toContainText(`MOTHER LIVE ${vessel} · 1.264, 103.82 · 12.4 kn · Last updated`);
    await expect(page.locator('.route-warning')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page).toHaveURL(`/freight-forwarder/shipments/${shipment.id}`);
  } finally {
    cleanup([owner.email], [identifier]);
  }
});

test('a stale fix stays on the map as the last known position', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The map is covered once on desktop.');
  test.setTimeout(90_000);
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const vessel = `Ever Steady ${randomBytes(3).toString('hex')}`;
  const identifier = mmsi();
  const shipment = await createShipment(request, owner.token, vessel);
  seedObservation(vessel, identifier, true);
  try {
    await signIn(page, owner.email);
    await openMap(page, shipment.id);
    await expect(page.locator('.route-warning')).toHaveText(LAST_KNOWN);
    await expect(page.getByRole('status')).toHaveText(LAST_KNOWN);
    await expect(page.locator('.route-live')).toContainText(`MOTHER LIVE ${vessel}`);
    await expect(page.locator('.route-live')).toContainText('Last updated');
    await expect(page.locator('.globe-pin.live.mother .globe-dot')).toBeVisible();
  } finally {
    cleanup([owner.email], [identifier]);
  }
});

test('a shipment with no retained position shows the no-position notice and no live marker', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The map is covered once on desktop.');
  test.setTimeout(90_000);
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const shipment = await createShipment(request, owner.token, `Ever Steady ${randomBytes(3).toString('hex')}`);
  try {
    await signIn(page, owner.email);
    const tracking = page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/shipments/${shipment.id}/tracking`);
    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    expect((await tracking).status()).toBe(200);
    await expect(page.locator('figure.route-chart')).toBeVisible();
    await expect(page.locator('.globe-pin.transshipment .globe-dot')).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('.route-warning')).toHaveText(NO_POSITION);
    await expect(page.locator('.globe-pin.live')).toHaveCount(0);
    await expect(page.locator('.route-live')).toHaveCount(0);
    await expect(page.getByText('No live AIS position', { exact: true }).first()).toBeVisible();
  } finally {
    cleanup([owner.email], []);
  }
});

test('another company cannot load the map or the tracking API', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The map is covered once on desktop.');
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const outsider = await registerAccount(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO');
  const vessel = `Ever Steady ${randomBytes(3).toString('hex')}`;
  const identifier = mmsi();
  const shipment = await createShipment(request, owner.token, vessel);
  seedObservation(vessel, identifier, false);
  try {
    const detail = await request.get(`/api/shipments/${shipment.id}`, {
      headers: { Authorization: `Bearer ${outsider.token}` },
    });
    expect(detail.status()).toBe(403);
    expect(await detail.json()).toMatchObject({ message: NO_ACCESS });

    const tracking = await request.get(`/api/shipments/${shipment.id}/tracking`, {
      headers: { Authorization: `Bearer ${outsider.token}` },
    });
    expect(tracking.status()).toBe(404);
    expect(await tracking.json()).toMatchObject({ message: NOT_FOUND });

    const anonymousDetail = await request.get(`/api/shipments/${shipment.id}`);
    expect(anonymousDetail.status()).toBe(401);
    expect(await anonymousDetail.json()).toMatchObject({ message: SESSION_ENDED });
    const anonymousTracking = await request.get(`/api/shipments/${shipment.id}/tracking`);
    expect(anonymousTracking.status()).toBe(401);
    expect(await anonymousTracking.json()).toMatchObject({ message: SESSION_ENDED });

    await signIn(page, outsider.email);
    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(page.getByRole('alert')).toHaveText(NO_ACCESS);
    await expect(page.getByRole('button', { name: 'Try again' })).toHaveCount(0);
    await expect(page.locator('figure.route-chart')).toHaveCount(0);
    await expect(page.locator('.globe-pin')).toHaveCount(0);
    await expect(page).toHaveURL(`/freight-forwarder/shipments/${shipment.id}`);

    await page.evaluate(() => sessionStorage.clear());
    await page.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(page).toHaveURL('/login');
    await expect(page.locator('figure.route-chart')).toHaveCount(0);
  } finally {
    cleanup([owner.email, outsider.email], [identifier]);
  }
});

test('drag and ctrl-wheel move the map without reloading the shipment', { tag: '@disposable-database' }, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The map is covered once on desktop.');
  test.setTimeout(90_000);
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO');
  const vessel = `Ever Steady ${randomBytes(3).toString('hex')}`;
  const identifier = mmsi();
  const shipment = await createShipment(request, owner.token, vessel);
  seedObservation(vessel, identifier, false);
  const url = `/freight-forwarder/shipments/${shipment.id}`;
  try {
    await signIn(page, owner.email);
    await openMap(page, shipment.id);
    await expect(page.locator('.globe-pin.departure .globe-dot')).toBeVisible();
    await expect(page.locator('.globe-pin.destination .globe-dot')).toBeVisible();
    const navigations = { count: 0 };
    page.on('framenavigated', frame => {
      if (frame === page.mainFrame()) navigations.count += 1;
    });

    const dot = page.locator('.globe-pin.live.mother .globe-dot');
    const canvas = page.locator('.route-chart-stage canvas');
    const canvasBox = await canvas.boundingBox();
    expect(canvasBox).not.toBeNull();
    const originX = canvasBox!.x + canvasBox!.width * 0.42;
    const originY = canvasBox!.y + 72;
    const samples: number[] = [];
    const first = await dot.boundingBox();
    expect(first).not.toBeNull();
    samples.push(first!.x);
    await page.mouse.move(originX, originY);
    await page.mouse.down();
    for (const step of [24, 48, 72]) {
      await page.mouse.move(originX + step, originY + 12, { steps: 6 });
      const box = await dot.boundingBox();
      expect(box, 'the vessel marker stays on the map while it is dragged').not.toBeNull();
      samples.push(box!.x);
    }
    await page.mouse.up();
    const pan = samples.join(' -> ');
    expect(samples[1], pan).toBeGreaterThan(samples[0] + 2);
    expect(samples[2], pan).toBeGreaterThan(samples[1] + 2);
    expect(samples[3], pan).toBeGreaterThan(samples[2] + 2);
    expect(samples[3] - samples[0], pan).toBeGreaterThan(20);
    await expect(page).toHaveURL(url);
    expect(navigations.count).toBe(0);

    const separation = async () => {
      const departure = await page.locator('.globe-pin.departure .globe-dot').boundingBox();
      const destination = await page.locator('.globe-pin.destination .globe-dot').boundingBox();
      expect(departure).not.toBeNull();
      expect(destination).not.toBeNull();
      return Math.hypot(departure!.x - destination!.x, departure!.y - destination!.y);
    };
    const settled = async () => {
      let previous = await separation();
      for (let attempt = 0; attempt < 12; attempt += 1) {
        await page.waitForTimeout(100);
        const next = await separation();
        if (Math.abs(next - previous) < 0.5) return next;
        previous = next;
      }
      return previous;
    };
    const beforeZoom = await settled();
    const zoomed: number[] = [beforeZoom];
    for (let step = 0; step < 3; step += 1) {
      await canvas.evaluate(node => {
        const rect = node.getBoundingClientRect();
        node.dispatchEvent(new WheelEvent('wheel', {
          deltaY: -180,
          ctrlKey: true,
          clientX: rect.left + rect.width / 2,
          clientY: rect.top + rect.height / 2,
          bubbles: true,
          cancelable: true,
        }));
      });
      zoomed.push(await settled());
    }
    expect(zoomed[1]).toBeGreaterThan(zoomed[0] + 4);
    expect(zoomed[2]).toBeGreaterThan(zoomed[1] + 4);
    expect(zoomed[3]).toBeGreaterThan(zoomed[2] + 4);
    await expect(dot).toBeVisible();
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
    await expect(page).toHaveURL(url);
    expect(navigations.count).toBe(0);
  } finally {
    cleanup([owner.email], [identifier]);
  }
});
