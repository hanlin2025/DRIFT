import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { IMPACT_NOT_RETURNED, RECOMMENDATION_RESPONSES } from '../src/shipment/recommendationCatalog';

const root = fileURLToPath(new URL('../../', import.meta.url));
const database = process.env.DRIFT_E2E_DATABASE ?? '';
const ownerToken = process.env.DRIFT_E2E_OWNER_TOKEN ?? '';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const password = 'Example123';
const NO_ACCESS = 'You do not have access to this shipment';
const IMPACT_NOT_RETURNED_DETAIL = 'The impact assessment has not been returned.';
const NO_PREDICTED_IMPACT = "Does not affect the importer's constraints";
const LOW_RISK = 'At least 24 hours to transfer cargo between vessels. The planned connection window is being used.';
const NO_ACTION = RECOMMENDATION_RESPONSES.NO_ACTION;
const MONITOR = RECOMMENDATION_RESPONSES.MONITOR_CONSTRAINTS;

function assertOwnedDatabase() {
  if (blocked.has(database) || !/^drift_cdg230_[0-9]{14}_[a-f0-9]{12}$/.test(database) || !/^[a-f0-9]{64}$/.test(ownerToken)) {
    throw new Error('Run this spec through npm run test:e2e:recommendation-decision. A database name is not proof that this run created the database.');
  }
  const current = sql('SELECT current_database();');
  const comment = sql("SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = current_database();");
  if (current !== database || comment !== ownerToken) {
    throw new Error('This database has no matching ownership record from the recommendation decision runner. Refusing to write fixtures.');
  }
}

const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function sql(query: string, variables: Record<string, string> = {}) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  if (useDocker) {
    return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common],
      { cwd: root, input: query, encoding: 'utf8' }).trim();
  }
  return execFileSync('psql', ['-h', '127.0.0.1', ...common], {
    cwd: root, input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  }).trim();
}

type Account = { email: string; token: string };

async function registerAccount(
  request: APIRequestContext,
  role: 'FREIGHT_FORWARDER' | 'IMPORTER',
  company: string,
  fullName: string,
): Promise<Account> {
  const invitation = randomBytes(32).toString('base64url');
  const email = `cdg230-e2e-${randomBytes(8).toString('hex')}@example.com`;
  sql(`INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, :'role', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = :'company';`, {
    email, role, company, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await request.post('/api/register', {
    data: { fullName, email, password, invitationToken: invitation },
  });
  expect(registered.status(), 'registration must reach the disposable database').toBe(201);
  const signedIn = await request.post('/api/login', { data: { email, password } });
  expect(signedIn.status()).toBe(200);
  return { email, token: (await signedIn.json()).token };
}

const itinerary = {
  origin: 'Rotterdam',
  destination: 'Jakarta',
  transshipmentPort: 'Singapore',
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-15T08:00:00+08:00',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-16T12:00:00+08:00',
};

async function createShipment(request: APIRequestContext, token: string) {
  const response = await request.post('/api/shipments', {
    headers: { Authorization: `Bearer ${token}` },
    data: { ...itinerary, shipmentReference: `CDG230-${randomBytes(4).toString('hex')}` },
  });
  const body = await response.text();
  expect(response.status(), body).toBe(201);
  return JSON.parse(body) as { id: number; shipmentReference: string };
}

function cleanup(emails: string[]) {
  for (const email of emails) {
    sql(`DELETE FROM shipment_risk_alert_deliveries
      WHERE user_id = (SELECT id FROM users WHERE email = :'email')
        OR transition_id IN (
          SELECT transition.id FROM shipment_risk_transitions transition
          JOIN shipments ON shipments.id = transition.shipment_id
          WHERE shipments.created_by_user_id = (SELECT id FROM users WHERE email = :'email'));
      DELETE FROM shipment_risk_transitions
      WHERE shipment_id IN (SELECT id FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email'));
      DELETE FROM shipments WHERE created_by_user_id = (SELECT id FROM users WHERE email = :'email');
      DELETE FROM invitations WHERE email = :'email';
      DELETE FROM users WHERE email = :'email';`, { email });
  }
}

async function signIn(page: Page, email: string, home: '/freight-forwarder' | '/importer') {
  await page.goto('/login');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /Log in/ }).click();
  await expect(page).toHaveURL(home);
}

function fact(page: Page, label: string) {
  return page.locator('.route-facts dl > div').filter({ has: page.getByText(label, { exact: true }) }).locator('dd');
}

async function openShipment(page: Page, role: 'freight-forwarder' | 'importer', shipment: { id: number; shipmentReference: string }) {
  await page.goto(`/${role}/shipments/${shipment.id}`);
  await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
}

async function recommendation(request: APIRequestContext, token: string, shipmentId: number) {
  const response = await request.get(`/api/shipments/${shipmentId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  return JSON.parse(body) as {
    risk: { level: string; explanation: string };
    recommendation: {
      available: boolean;
      unavailable: string | null;
      response: { code: string; label: string; wording: string } | null;
      impact: { outcome: string; detail: string | null };
    };
    recommendationDecision: {
      outcome: string;
      recommendationName: string;
      decidedByName: string;
      decidedAt: string;
      selectedResponseName: string | null;
    } | null;
  };
}

test.beforeAll(() => {
  assertOwnedDatabase();
});

test('approving an available recommendation shows that decision, who decided, and when, after the shipment is opened again', {
  tag: ['@disposable-database', '@completed-impact'],
}, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The decision page is covered once on desktop.');
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Ada Lee');
  try {
    const shipment = await createShipment(request, owner.token);
    const before = await recommendation(request, owner.token, shipment.id);
    expect(before.risk).toEqual({ level: 'LOW', explanation: LOW_RISK });
    expect(before.recommendation).toMatchObject({
      available: true,
      response: { code: 'NO_ACTION', label: NO_ACTION.label, wording: NO_ACTION.wording },
      impact: { outcome: 'NO_PREDICTED_IMPACT', detail: null },
    });

    await signIn(page, owner.email, '/freight-forwarder');
    await openShipment(page, 'freight-forwarder', shipment);
    await expect(page.getByRole('heading', { name: 'No action' })).toBeVisible();
    const recorded = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/recommendation-decision'));
    await page.getByRole('button', { name: 'Approve' }).click();
    const response = await recorded;
    expect(response.status(), await response.text()).toBe(200);
    expect(response.request().postDataJSON()).toEqual({ outcome: 'APPROVED' });

    const decision = page.getByRole('region', { name: 'Current decision' });
    await expect(decision.getByText('Approved', { exact: true })).toBeVisible();
    await expect(decision.getByText('No action', { exact: true })).toBeVisible();
    await expect(decision.getByText(NO_ACTION.wording)).toBeVisible();
    await expect(decision.getByText('Ada Lee')).toBeVisible();
    const when = await decision.locator('time').getAttribute('datetime');
    expect(when).toMatch(/^\d{4}-\d{2}-\d{2}T/);

    await page.reload();
    await expect(page.getByRole('heading', { name: shipment.shipmentReference })).toBeVisible();
    const again = page.getByRole('region', { name: 'Current decision' });
    await expect(again.getByText('Approved', { exact: true })).toBeVisible();
    await expect(again.getByText('Ada Lee')).toBeVisible();
    await expect(again.locator('time')).toHaveAttribute('datetime', when!);
    const stored = await recommendation(request, owner.token, shipment.id);
    expect(stored.recommendationDecision).toMatchObject({
      outcome: 'APPROVED',
      recommendationName: 'No action',
      decidedByName: 'Ada Lee',
      decidedAt: when,
    });
  } finally {
    cleanup([owner.email]);
  }
});

test('modifying a recommendation shows the chosen response instead of accepting the original', {
  tag: ['@disposable-database', '@completed-impact'],
}, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The decision page is covered once on desktop.');
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Ada Lee');
  try {
    const shipment = await createShipment(request, owner.token);
    await signIn(page, owner.email, '/freight-forwarder');
    await openShipment(page, 'freight-forwarder', shipment);
    await page.getByLabel('Modify with a different response').selectOption('MONITOR_CONSTRAINTS');
    const recorded = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/recommendation-decision'));
    await page.getByRole('button', { name: 'Modify', exact: true }).click();
    const response = await recorded;
    expect(response.status(), await response.text()).toBe(200);
    expect(response.request().postDataJSON()).toEqual({ outcome: 'MODIFIED', selectedResponse: 'MONITOR_CONSTRAINTS' });

    const decision = page.getByRole('region', { name: 'Current decision' });
    await expect(decision.getByText('Modified', { exact: true })).toBeVisible();
    await expect(decision.getByText('Approved', { exact: true })).toHaveCount(0);
    await expect(decision.locator('div').filter({ has: page.getByText('Chosen response', { exact: true }) })).toContainText(MONITOR.label);
    await expect(decision.locator('div').filter({ has: page.getByText('Chosen response', { exact: true }) })).toContainText(MONITOR.wording);
    await expect(decision.locator('div').filter({ has: page.getByText('Recommendation at the time', { exact: true }) })).toContainText('No action');
    await expect(decision.locator('div').filter({ has: page.getByText('Recommendation at the time', { exact: true }) })).toContainText(NO_ACTION.wording);
    await expect(page.getByRole('heading', { name: 'No action' })).toBeVisible();

    await page.reload();
    const again = page.getByRole('region', { name: 'Current decision' });
    await expect(again.getByText('Modified', { exact: true })).toBeVisible();
    await expect(again.getByText('Approved', { exact: true })).toHaveCount(0);
    await expect(again).toContainText(MONITOR.wording);
    await expect(again).toContainText(NO_ACTION.wording);
    const stored = await recommendation(request, owner.token, shipment.id);
    expect(stored.recommendationDecision).toMatchObject({
      outcome: 'MODIFIED',
      recommendationName: 'No action',
      selectedResponseName: 'Monitor',
    });
  } finally {
    cleanup([owner.email]);
  }
});

test('dismissing a recommendation sets it aside and leaves the recommendation, risk, and impact', {
  tag: ['@disposable-database', '@completed-impact'],
}, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The decision page is covered once on desktop.');
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Ada Lee');
  try {
    const shipment = await createShipment(request, owner.token);
    await signIn(page, owner.email, '/freight-forwarder');
    await openShipment(page, 'freight-forwarder', shipment);
    const recorded = page.waitForResponse(response =>
      response.request().method() === 'PATCH' && response.url().includes('/recommendation-decision'));
    await page.getByRole('button', { name: 'Dismiss' }).click();
    const response = await recorded;
    expect(response.status(), await response.text()).toBe(200);
    expect(response.request().postDataJSON()).toEqual({ outcome: 'DISMISSED' });

    const panel = page.getByRole('article', { name: 'Recommendation' });
    await expect(panel.getByRole('heading', { name: 'No action' })).toBeVisible();
    await expect(panel).toContainText(NO_ACTION.wording);
    await expect(panel.locator('.recommendation-impact').getByText(NO_PREDICTED_IMPACT, { exact: true })).toBeVisible();
    await expect(fact(page, 'Risk')).toContainText('Low');
    await expect(fact(page, 'Risk')).toContainText(LOW_RISK);
    await expect(panel.getByRole('region', { name: 'Current decision' })).toContainText('The recommendation was set aside');

    await page.reload();
    await expect(page.getByRole('article', { name: 'Recommendation' }).getByRole('heading', { name: 'No action' })).toBeVisible();
    await expect(page.locator('.recommendation-impact').getByText(NO_PREDICTED_IMPACT, { exact: true })).toBeVisible();
    await expect(fact(page, 'Risk')).toContainText(LOW_RISK);
    await expect(page.getByText('The recommendation was set aside')).toBeVisible();
    const stored = await recommendation(request, owner.token, shipment.id);
    expect(stored.risk.explanation).toBe(LOW_RISK);
    expect(stored.recommendation.response?.wording).toBe(NO_ACTION.wording);
    expect(stored.recommendation.impact).toEqual({ outcome: 'NO_PREDICTED_IMPACT', detail: null });
    expect(stored.recommendationDecision?.outcome).toBe('DISMISSED');
  } finally {
    cleanup([owner.email]);
  }
});

test('a linked importer sees the same current decision', {
  tag: ['@disposable-database', '@completed-impact'],
}, async ({ page, browser, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The decision page is covered once on desktop.');
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Ada Lee');
  const importer = await registerAccount(request, 'IMPORTER', 'STRAITS_FRESH_DEMO', 'Blake Lim');
  const importerPage = await browser.newPage();
  try {
    const shipment = await createShipment(request, owner.token);
    await signIn(page, owner.email, '/freight-forwarder');
    await openShipment(page, 'freight-forwarder', shipment);
    await page.getByRole('button', { name: 'Approve' }).click();
    const decision = page.getByRole('region', { name: 'Current decision' });
    await expect(decision.getByText('Approved', { exact: true })).toBeVisible();
    const when = await decision.locator('time').getAttribute('datetime');

    const importerCompanyId = Number(sql("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO';"));
    const linked = await request.patch(`/api/shipments/${shipment.id}/link-importer`, {
      headers: { Authorization: `Bearer ${owner.token}` },
      data: { importerCompanyId },
    });
    expect(linked.status(), await linked.text()).toBe(200);

    await signIn(importerPage, importer.email, '/importer');
    await openShipment(importerPage, 'importer', shipment);
    const same = importerPage.getByRole('region', { name: 'Current decision' });
    await expect(same.getByText('Approved', { exact: true })).toBeVisible();
    await expect(same.getByText('No action', { exact: true })).toBeVisible();
    await expect(same.getByText(NO_ACTION.wording)).toBeVisible();
    await expect(same.getByText('Ada Lee')).toBeVisible();
    await expect(same.locator('time')).toHaveAttribute('datetime', when!);
    await expect(importerPage.getByRole('heading', { name: 'No action' })).toBeVisible();
  } finally {
    await importerPage.close();
    cleanup([owner.email, importer.email]);
  }
});

test('another company receives 403 from the decision API and sees access denied without the decision or the shipment reference', {
  tag: ['@disposable-database', '@completed-impact'],
}, async ({ page, browser, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The decision page is covered once on desktop.');
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Ada Lee');
  const outsider = await registerAccount(request, 'FREIGHT_FORWARDER', 'STRAITS_FRESH_DEMO', 'Casey Outsider');
  const outsiderPage = await browser.newPage();
  try {
    const shipment = await createShipment(request, owner.token);
    await signIn(page, owner.email, '/freight-forwarder');
    await openShipment(page, 'freight-forwarder', shipment);
    await page.getByRole('button', { name: 'Approve' }).click();
    await expect(page.getByRole('region', { name: 'Current decision' }).getByText('Approved', { exact: true })).toBeVisible();

    const denied = await request.patch(`/api/shipments/${shipment.id}/recommendation-decision`, {
      headers: { Authorization: `Bearer ${outsider.token}` },
      data: { outcome: 'DISMISSED' },
    });
    expect(denied.status()).toBe(403);
    expect(await denied.json()).toEqual({ message: NO_ACCESS });

    await signIn(outsiderPage, outsider.email, '/freight-forwarder');
    await outsiderPage.goto(`/freight-forwarder/shipments/${shipment.id}`);
    await expect(outsiderPage.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    await expect(outsiderPage.getByText(NO_ACCESS)).toBeVisible();
    await expect(outsiderPage.getByText(shipment.shipmentReference)).toHaveCount(0);
    await expect(outsiderPage.getByRole('region', { name: 'Current decision' })).toHaveCount(0);
    await expect(outsiderPage.getByText('Approved', { exact: true })).toHaveCount(0);

    const stored = await recommendation(request, owner.token, shipment.id);
    expect(stored.recommendationDecision).toMatchObject({ outcome: 'APPROVED', decidedByName: 'Ada Lee' });
  } finally {
    await outsiderPage.close();
    cleanup([owner.email, outsider.email]);
  }
});

test('an unavailable recommendation shows the reason and no approve, modify, or dismiss control', {
  tag: ['@disposable-database', '@impact-not-returned'],
}, async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'The decision page is covered once on desktop.');
  const owner = await registerAccount(request, 'FREIGHT_FORWARDER', 'HARBOURLINE_DEMO', 'Ada Lee');
  try {
    const shipment = await createShipment(request, owner.token);
    const body = await recommendation(request, owner.token, shipment.id);
    expect(body.recommendation).toMatchObject({
      available: false,
      unavailable: IMPACT_NOT_RETURNED,
      response: null,
      impact: { outcome: 'UNAVAILABLE', detail: IMPACT_NOT_RETURNED_DETAIL },
    });
    expect(body.recommendationDecision).toBeNull();

    await signIn(page, owner.email, '/freight-forwarder');
    await openShipment(page, 'freight-forwarder', shipment);
    const panel = page.getByRole('article', { name: 'Recommendation' });
    await expect(panel.getByRole('heading', { name: 'Unavailable' })).toBeVisible();
    await expect(panel.getByText(IMPACT_NOT_RETURNED)).toBeVisible();
    await expect(panel.locator('.recommendation-impact').getByText(IMPACT_NOT_RETURNED_DETAIL, { exact: true })).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Approve' })).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Dismiss' })).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Modify' })).toHaveCount(0);
    await expect(panel.getByRole('heading', { name: 'No action' })).toHaveCount(0);
    await expect(page.getByText('The recommendation was set aside')).toHaveCount(0);
    await expect(fact(page, 'Risk')).toContainText(LOW_RISK);
  } finally {
    cleanup([owner.email]);
  }
});
