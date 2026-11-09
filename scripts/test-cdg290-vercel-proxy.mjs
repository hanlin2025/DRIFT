import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';

const apiOrigin = process.env.DRIFT_API_ORIGIN ?? 'http://127.0.0.1:18080';
const email = process.env.DRIFT_DEMO_EMAIL;
const password = process.env.DRIFT_DEMO_PASSWORD;
const inviteToken = process.env.DRIFT_DEMO_INVITE_TOKEN;
assert.ok(email && password, 'Set DRIFT_DEMO_EMAIL and DRIFT_DEMO_PASSWORD to isolated test-account values.');

const frontendRoot = resolve('frontend');
const indexHtml = await readFile(resolve(frontendRoot, 'dist/index.html'));
const vercelConfig = JSON.parse(await readFile(resolve(frontendRoot, 'vercel.json'), 'utf8'));
assert.ok(vercelConfig.rewrites[0].source === '/api', 'The exact /api rewrite must precede SPA fallback.');
assert.ok(vercelConfig.rewrites[1].source.startsWith('/api/'), 'The nested /api rewrite must precede SPA fallback.');
assert.equal(vercelConfig.rewrites.at(-1).destination, '/index.html', 'The final rewrite must support SPA deep links.');

const proxy = createServer(async (request, response) => {
  if (request.url === '/api' || request.url?.startsWith('/api/')) {
    try {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const headers = new Headers();
      for (const [key, value] of Object.entries(request.headers)) {
        if (value && !['host', 'connection', 'content-length', 'transfer-encoding'].includes(key.toLowerCase())) {
          headers.set(key, Array.isArray(value) ? value.join(', ') : value);
        }
      }
      const method = request.method ?? 'GET';
      const upstream = await fetch(new URL(request.url, apiOrigin), {
        method,
        headers,
        body: ['GET', 'HEAD'].includes(method) ? undefined : Buffer.concat(chunks),
      });
      response.statusCode = upstream.status;
      for (const [key, value] of upstream.headers) {
        if (!['connection', 'transfer-encoding'].includes(key.toLowerCase())) response.setHeader(key, value);
      }
      response.end(Buffer.from(await upstream.arrayBuffer()));
    } catch {
      response.statusCode = 502;
      response.end('Local Vercel rewrite harness could not reach the backend.');
    }
    return;
  }
  response.statusCode = 200;
  response.setHeader('content-type', 'text/html; charset=utf-8');
  response.setHeader('cache-control', 'no-cache');
  response.end(indexHtml);
});

await new Promise((resolveListen, reject) => {
  proxy.once('error', reject);
  proxy.listen(0, '127.0.0.1', resolveListen);
});
const localOrigin = `http://127.0.0.1:${proxy.address().port}`;
const checks = [];

async function call(path, { method = 'GET', token, body, headers = {} } = {}) {
  const response = await fetch(new URL(path, localOrigin), {
    method,
    headers: {
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  let json;
  try { json = text ? JSON.parse(text) : undefined; } catch { }
  return { response, json, text };
}

function passed(message) {
  checks.push(message);
}

try {
  const deepLink = await fetch(new URL('/shipments/987654?source=smoke', localOrigin));
  assert.equal(deepLink.status, 200);
  assert.match(await deepLink.text(), /<div id="root">/);
  passed('SPA deep link resolves to the frontend index');

  const denied = await call('/api/session');
  assert.equal(denied.response.status, 401, 'Unauthenticated API request must reach backend authorization.');
  passed('API authorization response is forwarded');

  if (inviteToken) {
    const registration = await call('/api/register', {
      method: 'POST',
      body: { fullName: 'DRIFT Isolated Deployment Smoke', email, password, invitationToken: inviteToken },
    });
    assert.equal(registration.response.status, 201, `Synthetic test account registration failed (${registration.response.status}).`);
    passed('POST registration body is forwarded to the isolated backend');
  }

  const login = await call('/api/login', { method: 'POST', body: { email, password } });
  assert.equal(login.response.status, 200, `Synthetic test login failed (${login.response.status}).`);
  assert.ok(login.json?.token, 'Login response must contain a bearer token.');
  const token = login.json.token;
  passed('POST login response is forwarded');

  const profile = await call('/api/profile', { token });
  assert.equal(profile.response.status, 200, `Authenticated GET failed (${profile.response.status}).`);
  assert.equal(profile.json?.email, email.toLowerCase());
  assert.match(profile.response.headers.get('cache-control') ?? '', /no-store/i);
  const session = await call('/api/session', { token });
  assert.equal(session.response.status, 200);
  assert.ok(session.json?.id && session.json?.company?.id);
  passed('Bearer authorization survives the same-origin API proxy');

  if (process.env.DRIFT_CREATE_DEMO_SHIPMENTS === 'true') {
    const suffix = randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase();
    const created = [];
    for (const key of ['A', 'B']) {
      const result = await call('/api/shipments', {
        method: 'POST',
        token,
        body: {
          shipmentReference: `DRIFT-SMOKE-${suffix}-${key}`,
          origin: 'Synthetic origin',
          destination: 'Synthetic destination',
          transshipmentPort: 'Singapore',
          motherVessel: `Synthetic mother ${key}`,
          plannedMotherArrivalAt: '2027-03-01T00:00:00Z',
          feederVessel: `Synthetic feeder ${key}`,
          plannedFeederDepartureAt: '2027-03-02T12:00:00Z',
        },
      });
      assert.equal(result.response.status, 201, `Authenticated shipment creation failed (${result.response.status}).`);
      assert.ok(result.json?.id, 'Shipment response did not include an ID.');
      created.push(result.json.id);
    }
    assert.notEqual(created[0], created[1]);
    passed('Authenticated POST creates two distinct synthetic shipment bindings');
    process.stdout.write(`DEPLOY_SMOKE_IDS=${JSON.stringify({ userId: session.json.id, companyId: session.json.company.id, shipmentAId: created[0], shipmentBId: created[1] })}\n`);
  }

  if (process.env.DRIFT_TEST_SCENARIO === 'true') {
    const shipmentAId = Number(process.env.DRIFT_SCENARIO_SHIPMENT_A_ID);
    const shipmentBId = Number(process.env.DRIFT_SCENARIO_SHIPMENT_B_ID);
    assert.ok(shipmentAId && shipmentBId && shipmentAId !== shipmentBId);
    const sessionId = randomUUID();
    const created = await call(`/api/scenarios/v1/sessions/${sessionId}`, {
      method: 'PUT', token, body: { anchorShipmentId: shipmentAId },
    });
    assert.equal(created.response.status, 200, `Scenario create failed (${created.response.status}).`);
    assert.equal(created.json?.state, 'UNSTARTED');
    const bound = created.json.shipments.map(item => [item.scenarioShipmentKey, Number(item.shipmentId)]);
    assert.deepEqual(bound, [['A', shipmentAId], ['B', shipmentBId]]);
    const read = await call(`/api/scenarios/v1/sessions/${sessionId}`, { token });
    assert.equal(read.response.status, 200);
    assert.equal(read.json?.state, 'UNSTARTED');
    passed('Scenario session creation, GET and A/B shipment binding');

    const start = await command('start', read.json, token, sessionId, 'start');
    assert.equal(start.state, 'ACTIVE');
    assert.equal(start.snapshot?.stage, 'BASELINE');
    assert.ok(start.snapshot.aisObservations.every(row => row.sourceMode === 'SYNTHETIC_DEMONSTRATION'));
    const change = await command('next', start, token, sessionId, 'change');
    assert.equal(change.snapshot?.stage, 'CHANGE');
    const worsened = await command('next', change, token, sessionId, 'worsened');
    assert.equal(worsened.state, 'COMPLETE');
    assert.equal(worsened.snapshot?.stage, 'WORSENED');
    passed('Start → BASELINE → CHANGE → WORSENED lifecycle with synthetic vessel positions');

    const reset = await command('reset', worsened, token, sessionId, 'reset');
    assert.equal(reset.state, 'UNSTARTED');
    assert.equal(reset.revision, 0);
    assert.notEqual(reset.epochId, worsened.epochId);
    const staleEpoch = await commandResult('start', sessionId, token,
      { expectedEpochId: worsened.epochId, expectedRevision: 3 }, 'stale-epoch');
    assert.equal(staleEpoch.response.status, 409);
    assert.match(JSON.stringify(staleEpoch.json), /STALE_EPOCH/);
    const staleRevision = await commandResult('start', sessionId, token,
      { expectedEpochId: reset.epochId, expectedRevision: 99 }, 'stale-revision');
    assert.equal(staleRevision.response.status, 409);
    assert.match(JSON.stringify(staleRevision.json), /STALE_REVISION/);
    passed('Reset creates a fresh epoch and rejects stale epoch/revision commands');

    if (process.env.DRIFT_UNAUTHORIZED_EMAIL && process.env.DRIFT_UNAUTHORIZED_PASSWORD && process.env.DRIFT_UNAUTHORIZED_INVITE) {
      const registration = await call('/api/register', {
        method: 'POST',
        body: {
          fullName: 'DRIFT Isolated Unauthorized Smoke',
          email: process.env.DRIFT_UNAUTHORIZED_EMAIL,
          password: process.env.DRIFT_UNAUTHORIZED_PASSWORD,
          invitationToken: process.env.DRIFT_UNAUTHORIZED_INVITE,
        },
      });
      assert.equal(registration.response.status, 201);
      const otherLogin = await call('/api/login', {
        method: 'POST', body: { email: process.env.DRIFT_UNAUTHORIZED_EMAIL, password: process.env.DRIFT_UNAUTHORIZED_PASSWORD },
      });
      assert.equal(otherLogin.response.status, 200);
      const forbidden = await call(`/api/scenarios/v1/sessions/${randomUUID()}`, {
        method: 'PUT', token: otherLogin.json.token, body: { anchorShipmentId: shipmentAId },
      });
      assert.equal(forbidden.response.status, 403, `Unauthorized scenario user was not denied (${forbidden.response.status}).`);
      passed('Different-company demo user is denied scenario access');
    }
  }

  for (const check of checks) process.stdout.write(`PASS ${check}\n`);
} finally {
  await new Promise(resolveClose => proxy.close(resolveClose));
}

async function command(action, state, token, sessionId, label) {
  const result = await commandResult(action, sessionId, token,
    { expectedEpochId: state.epochId, expectedRevision: state.revision }, label);
  assert.equal(result.response.status, 200, `Scenario ${action} failed (${result.response.status}).`);
  return result.json;
}

async function commandResult(action, sessionId, token, body, label) {
  return await call(`/api/scenarios/v1/sessions/${sessionId}/${action}`, {
    method: 'POST', token, body, headers: { 'Idempotency-Key': `deployment-readiness-${label}` },
  });
}
