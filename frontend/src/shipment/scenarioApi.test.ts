import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import {
  advanceScenarioSession, compareScenarioVersions, createScenarioCommand, createScenarioSession,
  createScenarioSessionId, getScenarioSession, resetScenarioSession, ScenarioApiError, startScenarioSession,
  type ScenarioCommand, type ScenarioErrorCode, type ScenarioSessionEnvelope, type ScenarioStage,
} from './scenarioApi';

const sessionId = 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf';
const epochId = '74dab1c6-1d4b-4889-8dde-0b31986fed28';
const resetEpochId = '4618e3c7-2006-4901-a211-01fbbaf8186c';
const snapshotId = '063052b2-fca1-4116-b559-f387ac6c2dc7';
const path = `/api/scenarios/v1/sessions/${sessionId}`;
const bindings = [
  { scenarioShipmentKey: 'A', shipmentId: 142, shipmentReference: 'DEMO-A-PORTFOLIO', scenarioShipmentReference: 'DEMO-CDG279-Api-A' },
  { scenarioShipmentKey: 'B', shipmentId: 143, shipmentReference: 'DEMO-B-PORTFOLIO', scenarioShipmentReference: 'DEMO-CDG279-Api-B' },
] as const;

function unstarted(epoch = epochId): ScenarioSessionEnvelope {
  return {
    apiVersion: '1', sessionId, scenarioId: 'CDG279-Api', scenarioContractVersion: 'cdg279.synthetic-disruption.v1',
    state: 'UNSTARTED', epochId: epoch, revision: 0,
    capabilities: { canRead: true, canStart: true, canAdvance: false, canReset: false, canGenerateAssessment: false },
    shipments: bindings, snapshot: null,
  };
}
function staged(stage: ScenarioStage = 'BASELINE', epoch = epochId): ScenarioSessionEnvelope {
  const stageOrder = ({ BASELINE: 0, CHANGE: 1, WORSENED: 2 } as const)[stage];
  const revision = stageOrder + 1;
  const cutoff = `2026-10-01T0${stageOrder * 4}:00:00Z`;
  return {
    ...unstarted(epoch), state: stage === 'WORSENED' ? 'COMPLETE' : 'ACTIVE', revision,
    capabilities: { canRead: true, canStart: false, canAdvance: stage !== 'WORSENED', canReset: true, canGenerateAssessment: false },
    snapshot: {
      snapshotId, epochId: epoch, revision, stage, stageOrder, stageCount: 3, fixtureStageId: 'fixture-stage',
      simulationCutoffUtc: cutoff, mapPlaybackCutoffUtc: cutoff,
      shipments: bindings.map(binding => ({
        scenarioShipmentKey: binding.scenarioShipmentKey, sourceShipmentId: binding.shipmentId,
        scenarioShipmentReference: binding.scenarioShipmentReference, transshipmentPort: 'Fictional Port',
        plannedMotherArrivalAt: '2026-10-01T06:00:00+00:00', plannedFeederDepartureAt: '2026-10-02T12:00:00Z',
        plannedConnectionWindow: { status: 'AVAILABLE', window: { duration: '30 hours', totalSeconds: 108000 } },
        remainingConnectionWindow: { status: 'AVAILABLE', duration: '30 hours', totalSeconds: 108000, missedBy: null, missedBySeconds: null },
        arrivalEstimate: { estimatedArrivalAt: '2026-10-01T06:00:00Z', unavailableReason: null },
        risk: { level: 'LOW', explanation: 'Server-calculated fact.' },
        businessImpact: { outcome: 'NO_PREDICTED_IMPACT', reason: null, deadline: null },
      })),
      aisObservations: (['MOTHER', 'FEEDER'] as const).map(vesselRole => ({
        vesselRole, observedAtUtc: '2026-09-30T23:58:00.123456789Z', receivedAtUtc: '2026-09-30T23:59:00Z',
        sourceMode: 'SYNTHETIC_DEMONSTRATION',
        position: { mmsi: '900000001', latitude: 2.26, longitude: 103.4, speedOverGroundKnots: null,
          courseOverGroundDegrees: null, trueHeadingDegrees: null, navigationalStatus: null, positionValid: true,
          aisUtcSecond: null, ingestedAt: '2026-09-30T23:59:00Z', source: 'SEEDED' },
      })),
      maritimeContext: {
        enabled: true, sourceMode: 'SYNTHETIC_DEMONSTRATION', assessmentCutoffUtc: cutoff,
        evidence: [{ evidenceId: 'fixture-evidence', observedAt: '2026-09-30T23:50:00Z',
          retrievedAtUtc: '2026-09-30T23:55:00Z', sourceEventAt: null }], notices: [], aiExplanation: null, suggestedActions: null,
      },
    },
  };
}
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
function mockResponse(body: unknown, status = 200) {
  const fetchMock = vi.fn().mockImplementation(async () => json(body, status));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}
function command(context = unstarted()): ScenarioCommand {
  return createScenarioCommand(context, 'retained-command-key');
}
function mutable(value: ScenarioSessionEnvelope): any {
  // Deliberately malformed wire values only; never used by production code.
  return structuredClone(value);
}

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); vi.restoreAllMocks(); });

describe('v1 scenario transport', () => {
  it('generates client UUIDs only when explicitly requested', () => {
    const first = createScenarioSessionId();
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(createScenarioSessionId()).not.toBe(first);
  });
  it('PUTs an anchor with bearer auth and recovers an UNSTARTED null snapshot by GET', async () => {
    const fetchMock = mockResponse(unstarted());
    const created = await createScenarioSession('token', sessionId, { anchorShipmentId: 142 });
    const recovered = await getScenarioSession('token', sessionId);
    expect(created).toEqual(unstarted());
    expect(recovered.snapshot).toBeNull();
    expect(fetchMock).toHaveBeenNthCalledWith(1, path, expect.objectContaining({
      method: 'PUT', cache: 'no-store', body: '{"anchorShipmentId":142}', signal: expect.any(AbortSignal),
      headers: { Authorization: 'Bearer token', 'Content-Type': 'application/json' },
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, path, expect.objectContaining({
      method: 'GET', cache: 'no-store', body: undefined, headers: { Authorization: 'Bearer token' },
    }));
  });
  it('uses same-origin cookie authentication when the browser does not retain a bearer token', async () => {
    const fetchMock = mockResponse(unstarted());
    await createScenarioSession('', sessionId, { anchorShipmentId: 142 });
    expect(fetchMock).toHaveBeenCalledWith(path, expect.objectContaining({
      method: 'PUT', credentials: 'include',
      headers: { 'X-DRIFT-Request': '1', 'Content-Type': 'application/json' },
    }));
  });
  it('allows repeated PUT recovery of an already active session', async () => {
    mockResponse(staged('CHANGE'));
    expect(await createScenarioSession('token', sessionId, { anchorShipmentId: 142 })).toEqual(staged('CHANGE'));
  });
  it.each(['BASELINE', 'CHANGE', 'WORSENED'] as const)('reads %s metadata and untouched server facts', async stage => {
    const envelope = staged(stage);
    mockResponse(envelope);
    expect(await getScenarioSession('token', sessionId)).toEqual(envelope);
  });
  it.each([
    [startScenarioSession, 'start', staged('BASELINE')],
    [advanceScenarioSession, 'next', staged('CHANGE')],
    [resetScenarioSession, 'reset', unstarted(resetEpochId)],
  ] as const)('sends the correct command to %s /%s', async (send, action, result) => {
    const prepared = command(staged('BASELINE'));
    const fetchMock = mockResponse(result);
    expect(await send('token', sessionId, prepared)).toEqual(result);
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(`${path}/${action}`, expect.objectContaining({
      method: 'POST', cache: 'no-store', body: JSON.stringify(prepared.request),
      headers: { Authorization: 'Bearer token', 'Content-Type': 'application/json', 'Idempotency-Key': prepared.idempotencyKey },
    }));
  });
  it('keeps A/B persistent IDs distinct from fictional references, including reversed wire ordering', async () => {
    const envelope = mutable(staged());
    envelope.shipments.reverse();
    mockResponse(envelope);
    const received = await getScenarioSession('token', sessionId);
    const a = received.shipments.find(row => row.scenarioShipmentKey === 'A');
    expect(a).toEqual(bindings[0]);
    expect(received.snapshot?.shipments[0].sourceShipmentId).toBe(a?.shipmentId);
    expect(a?.shipmentReference).not.toBe(a?.scenarioShipmentReference);
  });
  it('preserves cutoffs, original nanosecond source timestamps, provenance and nullable domain fields', async () => {
    const envelope = staged('CHANGE');
    mockResponse(envelope);
    const result = await getScenarioSession('token', sessionId);
    expect(result.snapshot).toEqual(envelope.snapshot);
    expect(result.snapshot?.aisObservations[0].observedAtUtc).toBe('2026-09-30T23:58:00.123456789Z');
    expect(result.snapshot?.aisObservations[0].position.speedOverGroundKnots).toBeNull();
    expect(result.snapshot?.aisObservations[0].position.ingestedAt).toBe('2026-09-30T23:59:00Z');
    expect(result.snapshot?.aisObservations[0].position.source).toBe('SEEDED');
    expect(result.snapshot?.mapPlaybackCutoffUtc).toBe('2026-10-01T04:00:00Z');
  });
  it('preserves unavailable/optional domain fields as opaque facts without inventing defaults', async () => {
    const envelope = mutable(staged());
    envelope.snapshot.shipments[0].arrivalEstimate = { estimatedArrivalAt: null, unavailableReason: { code: 'NO_REPORT', message: 'Unavailable.' } };
    envelope.snapshot.maritimeContext = { enabled: false, evidence: [], aiExplanation: null };
    delete envelope.snapshot.aisObservations[0].position.aisUtcSecond;
    mockResponse(envelope);
    expect(await getScenarioSession('token', sessionId)).toEqual(envelope);
  });
  it('passes capability denials through without inventing capabilities or issuing another request', async () => {
    const envelope = mutable(staged());
    envelope.capabilities = { canRead: false, canStart: false, canAdvance: false, canReset: false, canGenerateAssessment: false };
    const fetchMock = mockResponse(envelope);
    expect((await getScenarioSession('token', sessionId)).capabilities).toEqual(envelope.capabilities);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('performs only the five explicitly requested requests; no AI, track, provider or polling calls', async () => {
    const fetchMock = mockResponse(unstarted());
    await createScenarioSession('token', sessionId, { anchorShipmentId: 142 });
    await getScenarioSession('token', sessionId);
    await startScenarioSession('token', sessionId, command());
    await advanceScenarioSession('token', sessionId, command());
    await resetScenarioSession('token', sessionId, command());
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([path, path, `${path}/start`, `${path}/next`, `${path}/reset`]);
  });
});

describe('retry and reconciliation contract', () => {
  it('retains the exact key and frozen request across a lost reply and an explicit retry', async () => {
    const prepared = command();
    expect(Object.isFrozen(prepared)).toBe(true);
    expect(Object.isFrozen(prepared.request)).toBe(true);
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError('Lost response')).mockResolvedValueOnce(json(staged()));
    vi.stubGlobal('fetch', fetchMock);
    await expect(startScenarioSession('token', sessionId, prepared)).rejects.toMatchObject({
      kind: 'network', status: 0, outcomeUnknown: true, requiresReconciliation: true,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await startScenarioSession('token', sessionId, prepared);
    const first = fetchMock.mock.calls[0][1];
    const second = fetchMock.mock.calls[1][1];
    expect(second.body).toBe(first.body);
    expect(second.headers).toEqual(first.headers);
    expect(second.body).toBe(JSON.stringify({ expectedEpochId: epochId, expectedRevision: 0 }));
  });
  it('returns an old successful replay unchanged and identifies it as older than accepted state', async () => {
    const accepted = staged('WORSENED');
    mockResponse(staged('BASELINE'));
    const replay = await startScenarioSession('token', sessionId, command());
    expect(replay.revision).toBe(1);
    expect(compareScenarioVersions(accepted, replay)).toBe('older');
  });
  it('does not order opaque epochs, even if the replay revision is larger or is a Reset response', async () => {
    const accepted = unstarted(resetEpochId);
    mockResponse(staged('WORSENED'));
    const replay = await advanceScenarioSession('token', sessionId, command());
    expect(compareScenarioVersions(accepted, replay)).toBe('different-epoch');
    expect(compareScenarioVersions(staged('WORSENED'), accepted)).toBe('different-epoch');
    mockResponse(unstarted());
    const resetReplay = await resetScenarioSession('token', sessionId, command());
    expect(compareScenarioVersions(accepted, resetReplay)).toBe('different-epoch');
  });
  it('compares same-epoch revisions and rejects a different session regardless of revision', () => {
    expect(compareScenarioVersions(staged(), staged('CHANGE'))).toBe('newer');
    expect(compareScenarioVersions(staged(), staged())).toBe('equal');
    expect(compareScenarioVersions(staged(), { ...staged(), sessionId: resetEpochId, revision: 99 })).toBe('different-session');
  });
});

describe('typed backend errors', () => {
  const errors: readonly [number, ScenarioErrorCode][] = [
    [400, 'INVALID_REQUEST'], [401, 'UNAUTHENTICATED'], [403, 'SCENARIO_ACCESS_DENIED'],
    [404, 'SCENARIO_DISABLED'], [404, 'SCENARIO_NOT_FOUND'], [409, 'STALE_EPOCH'], [409, 'STALE_REVISION'],
    [409, 'IDEMPOTENCY_KEY_REUSED'], [409, 'SESSION_ID_REUSED'], [409, 'SCENARIO_COMPLETE'],
    [409, 'SCENARIO_NOT_STARTED'], [429, 'SESSION_COMMAND_LIMIT'], [503, 'SCENARIO_NOT_CONFIGURED'], [503, 'SESSION_CAPACITY'],
  ];
  it.each(errors)('preserves HTTP %i / %s without an automatic retry', async (status, code) => {
    const fetchMock = mockResponse({ code, message: 'Backend explanation.', retryable: false }, status);
    const pending = advanceScenarioSession('token', sessionId, command());
    await expect(pending).rejects.toBeInstanceOf(ApiError);
    await expect(pending).rejects.toBeInstanceOf(ScenarioApiError);
    await expect(pending).rejects.toMatchObject({
      code, backendCode: code, status, kind: 'api', retryable: false,
      requiresReconciliation: code === 'STALE_EPOCH' || code === 'STALE_REVISION' || status >= 500,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('retains validation fieldErrors using the existing ApiError.fields convention', async () => {
    mockResponse({ code: 'INVALID_REQUEST', message: 'Invalid.', retryable: false,
      fieldErrors: { expectedRevision: 'must be non-negative', ignored: 12 } }, 400);
    await expect(startScenarioSession('token', sessionId, command())).rejects.toMatchObject({ fields: { expectedRevision: 'must be non-negative' } });
  });
  it('does not invent an expiry code for the concealed 404', async () => {
    mockResponse({ code: 'SCENARIO_NOT_FOUND', message: 'Scenario session not found', retryable: false }, 404);
    await expect(getScenarioSession('token', sessionId)).rejects.toMatchObject({ code: 'SCENARIO_NOT_FOUND', outcomeUnknown: false });
  });
  it('retains future backend codes without claiming a known code or retrying', async () => {
    const fetchMock = mockResponse({ code: 'FUTURE_CODE', message: 'Future.', retryable: true }, 503);
    await expect(getScenarioSession('token', sessionId)).rejects.toMatchObject({ code: null, backendCode: 'FUTURE_CODE', retryable: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([401, 403, 404, 429, 503])('preserves HTTP %i when a proxy returns no JSON code', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Proxy error', { status })));
    await expect(getScenarioSession('token', sessionId)).rejects.toMatchObject({ status, code: null, kind: 'http' });
  });
});

describe('malformed responses', () => {
  const cases: [string, (value: any) => void][] = [
    ['API version', v => { v.apiVersion = '2'; }],
    ['contract version', v => { v.scenarioContractVersion = 'future'; }],
    ['session identity', v => { v.sessionId = resetEpochId; }],
    ['missing epoch', v => { delete v.epochId; }],
    ['invalid epoch', v => { v.epochId = 'not-a-uuid'; }],
    ['unsafe revision', v => { v.revision = Number.MAX_SAFE_INTEGER + 1; }],
    ['lifecycle', v => { v.state = 'BASELINE'; }],
    ['missing snapshot', v => { delete v.snapshot; }],
    ['null active snapshot', v => { v.snapshot = null; }],
    ['missing capability', v => { delete v.capabilities.canRead; }],
    ['AI capability', v => { v.capabilities.canGenerateAssessment = true; }],
    ['missing binding', v => { v.shipments.pop(); }],
    ['duplicate key', v => { v.shipments[1].scenarioShipmentKey = 'A'; }],
    ['duplicate persistent ID', v => { v.shipments[1].shipmentId = 142; }],
    ['fictional persistent ID', v => { v.shipments[0].shipmentId = 'DEMO-CDG279-Api-A'; }],
    ['snapshot epoch mismatch', v => { v.snapshot.epochId = resetEpochId; }],
    ['snapshot revision mismatch', v => { v.snapshot.revision = 2; }],
    ['missing cutoff', v => { delete v.snapshot.simulationCutoffUtc; }],
    ['invalid cutoff', v => { v.snapshot.mapPlaybackCutoffUtc = 'yesterday'; }],
    ['timezone-free cutoff', v => { v.snapshot.simulationCutoffUtc = '2026-10-01T00:00:00'; }],
    ['stage', v => { v.snapshot.stage = 'FUTURE'; }],
    ['stage order', v => { v.snapshot.stageOrder = 3; }],
    ['stage count', v => { v.snapshot.stageCount = 4; }],
    ['snapshot binding mismatch', v => { v.snapshot.shipments[0].sourceShipmentId = 99; }],
    ['snapshot reference mismatch', v => { v.snapshot.shipments[0].scenarioShipmentReference = 'wrong'; }],
    ['missing risk object', v => { delete v.snapshot.shipments[0].risk; }],
    ['null maritime context', v => { v.snapshot.maritimeContext = null; }],
    ['null position', v => { v.snapshot.aisObservations[0].position = null; }],
    ['missing observed timestamp', v => { delete v.snapshot.aisObservations[0].observedAtUtc; }],
    ['missing source mode', v => { delete v.snapshot.aisObservations[0].sourceMode; }],
    ['duplicate vessel role', v => { v.snapshot.aisObservations[1].vesselRole = 'MOTHER'; }],
    ['inconsistent final lifecycle', v => { v.state = 'COMPLETE'; }],
  ];
  it.each(cases)('rejects malformed %s', async (_, mutate) => {
    const value = mutable(staged());
    mutate(value);
    mockResponse(value);
    await expect(getScenarioSession('token', sessionId)).rejects.toMatchObject({ kind: 'malformed-response', status: 502, outcomeUnknown: false });
  });
  it.each([null, [], {}, { ...unstarted(), revision: 1 }, { ...unstarted(), snapshot: staged().snapshot }])('rejects malformed envelopes %j', async value => {
    mockResponse(value);
    await expect(getScenarioSession('token', sessionId)).rejects.toBeInstanceOf(ScenarioApiError);
  });
  it.each([
    new Response('bad json', { headers: { 'Content-Type': 'application/json' } }),
    new Response('<html>proxy</html>', { headers: { 'Content-Type': 'text/html' } }),
  ])('marks an unreadable command success as an unknown outcome', async response => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));
    await expect(startScenarioSession('token', sessionId, command())).rejects.toMatchObject({ kind: 'malformed-response', outcomeUnknown: true });
  });
});

describe('bounded requests and cancellation', () => {
  function abortableFetch() {
    const fetchMock = vi.fn((_url: string, init: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
    }));
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }
  it('honors caller cancellation after dispatch and leaves a command outcome unknown', async () => {
    const fetchMock = abortableFetch();
    const controller = new AbortController();
    const pending = startScenarioSession('token', sessionId, command(), { signal: controller.signal });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ kind: 'cancelled', status: 0, outcomeUnknown: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('does not dispatch an already cancelled command', async () => {
    const fetchMock = abortableFetch();
    const controller = new AbortController();
    controller.abort();
    await expect(startScenarioSession('token', sessionId, command(), { signal: controller.signal }))
      .rejects.toMatchObject({ kind: 'cancelled', outcomeUnknown: false });
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('times out after 15 seconds without retrying', async () => {
    vi.useFakeTimers();
    const fetchMock = abortableFetch();
    const pending = startScenarioSession('token', sessionId, command());
    const assertion = expect(pending).rejects.toMatchObject({ kind: 'timeout', outcomeUnknown: true });
    await vi.advanceTimersByTimeAsync(15000);
    await assertion;
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('keeps the timeout active until the body finishes', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_url: string, init: RequestInit) => Promise.resolve({
      ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }),
      json: () => new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
      }),
    }));
    vi.stubGlobal('fetch', fetchMock);
    const pending = getScenarioSession('token', sessionId);
    const assertion = expect(pending).rejects.toMatchObject({ kind: 'timeout', outcomeUnknown: false });
    await vi.advanceTimersByTimeAsync(15000);
    await assertion;
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('cleans up timers and cancellation listeners after success', async () => {
    vi.useFakeTimers();
    mockResponse(unstarted());
    const controller = new AbortController();
    const remove = vi.spyOn(controller.signal, 'removeEventListener');
    await getScenarioSession('token', sessionId, { signal: controller.signal });
    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });
});
