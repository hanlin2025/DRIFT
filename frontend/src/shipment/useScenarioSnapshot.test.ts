import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './scenarioApi';
import { useScenarioSnapshot, type UseScenarioSnapshotOptions } from './useScenarioSnapshot';

vi.mock('./scenarioApi', async importOriginal => ({
  ...await importOriginal<typeof import('./scenarioApi')>(),
  createScenarioSessionId: vi.fn(), createScenarioSession: vi.fn(), getScenarioSession: vi.fn(),
  startScenarioSession: vi.fn(), advanceScenarioSession: vi.fn(), resetScenarioSession: vi.fn(),
}));
const sid = 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf';
const otherSid = '0915b4a8-c0f1-4fb0-9000-7153f8a89fbf';
const epoch = '74dab1c6-1d4b-4889-8dde-0b31986fed28';
const newEpoch = '4618e3c7-2006-4901-a211-01fbbaf8186c';
const hintKey = 'drift.scenario.v1:7:11:142';
const options: UseScenarioSnapshotOptions = {
  viewer: { id: 7, token: 'token', company: { id: 11, code: 'DEMO', name: 'Demo' } }, anchorShipmentId: 142,
};
function envelope(stage?: api.ScenarioStage, epochId = epoch, sessionId = sid): api.ScenarioSessionEnvelope {
  const stageOrder = stage === 'CHANGE' ? 1 : stage === 'WORSENED' ? 2 : 0;
  const revision = stage ? stageOrder + 1 : 0;
  const shipments: api.ScenarioShipmentBinding[] = ['A', 'B'].map((key, i) => ({
    scenarioShipmentKey: key as api.ScenarioShipmentKey, shipmentId: 142 + i,
    shipmentReference: `ORDINARY-${key}`, scenarioShipmentReference: `FICTIONAL-${key}`,
  }));
  const base = { apiVersion: '1' as const, sessionId, scenarioId: 'CDG279-Api',
    scenarioContractVersion: 'cdg279.synthetic-disruption.v1' as const, epochId, revision, shipments,
    capabilities: { canRead: true, canStart: !stage, canAdvance: !!stage && stage !== 'WORSENED',
      canReset: !!stage, canGenerateAssessment: false as const } };
  if (!stage) return { ...base, state: 'UNSTARTED', snapshot: null };
  return { ...base, state: stage === 'WORSENED' ? 'COMPLETE' : 'ACTIVE', snapshot: {
    snapshotId: '063052b2-fca1-4116-b559-f387ac6c2dc7', epochId, revision, stage, stageOrder, stageCount: 3,
    fixtureStageId: stage, simulationCutoffUtc: `2026-10-01T0${stageOrder * 4}:00:00Z`,
    mapPlaybackCutoffUtc: `2026-10-01T0${stageOrder * 4}:00:00Z`,
    shipments: shipments.map(binding => ({ scenarioShipmentKey: binding.scenarioShipmentKey,
      sourceShipmentId: binding.shipmentId, scenarioShipmentReference: binding.scenarioShipmentReference,
      transshipmentPort: 'Fictional port', plannedMotherArrivalAt: '2026-10-01T06:00:00Z',
      plannedFeederDepartureAt: '2026-10-02T12:00:00Z', plannedConnectionWindow: {}, remainingConnectionWindow: {},
      arrivalEstimate: {}, risk: {}, businessImpact: {} })),
    aisObservations: (['MOTHER', 'FEEDER'] as const).map(vesselRole => ({ vesselRole,
      observedAtUtc: '2026-10-01T00:00:00Z', receivedAtUtc: '2026-10-01T00:00:00Z',
      sourceMode: 'SYNTHETIC_DEMONSTRATION', position: { source: 'SEEDED', speedOverGroundKnots: null } })),
    maritimeContext: { evidence: [], aiExplanation: null },
  } };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function problem(status: number, code: api.ScenarioErrorCode, unknown = false) {
  return new api.ScenarioApiError(code, status, 'api', code, false, unknown);
}
let server: api.ScenarioSessionEnvelope;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  server = envelope();
  vi.mocked(api.createScenarioSessionId).mockReturnValue(sid);
  vi.mocked(api.createScenarioSession).mockImplementation(async (_token, sessionId) => ({ ...server, sessionId }));
  vi.mocked(api.getScenarioSession).mockImplementation(async (_token, sessionId) => ({ ...server, sessionId }));
  vi.mocked(api.startScenarioSession).mockImplementation(async () => { server = envelope('BASELINE'); return server; });
  vi.mocked(api.advanceScenarioSession).mockImplementation(async () => {
    server = envelope(server.snapshot?.stage === 'BASELINE' ? 'CHANGE' : 'WORSENED'); return server;
  });
  vi.mocked(api.resetScenarioSession).mockImplementation(async () => { server = envelope(undefined, newEpoch); return server; });
  vi.stubGlobal('fetch', vi.fn(() => { throw new Error('Unexpected HTTP request'); }));
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
async function ready(props = options) {
  const hook = renderHook(useScenarioSnapshot, { initialProps: props });
  await waitFor(() => expect(hook.result.current.status).toBe('ready'));
  return hook;
}

describe('session identity and recovery', () => {
  it('creates using the authorized anchor and stores only a tab-scoped UUID hint', async () => {
    const { result } = await ready();
    expect(api.createScenarioSession).toHaveBeenCalledExactlyOnceWith('token', sid, { anchorShipmentId: 142 }, { signal: expect.any(AbortSignal) });
    expect(api.getScenarioSession).not.toHaveBeenCalled();
    expect(result.current.session?.state).toBe('UNSTARTED');
    expect(result.current.snapshot).toBeNull();
    expect(result.current.binding?.shipmentReference).toBe('ORDINARY-A');
    expect(sessionStorage.length).toBe(1);
    expect(sessionStorage.getItem(hintKey)).toBe(sid);
    expect(sessionStorage.getItem(hintKey)).not.toContain('token');
  });
  it('creates a session for a cookie-authenticated browser with no retained bearer token', async () => {
    const { result } = await ready({ ...options, viewer: { ...options.viewer!, token: '' } });
    expect(api.createScenarioSession).toHaveBeenCalledExactlyOnceWith('', sid, { anchorShipmentId: 142 }, { signal: expect.any(AbortSignal) });
    expect(result.current.status).toBe('ready');
    expect(result.current.session?.state).toBe('UNSTARTED');
    expect(sessionStorage.getItem(hintKey)).toBe(sid);
  });
  it('recovers the same session on remount and keeps B progression independent', async () => {
    server = envelope('CHANGE');
    sessionStorage.setItem(hintKey, sid);
    const first = await ready();
    first.unmount();
    server = envelope();
    vi.mocked(api.createScenarioSessionId).mockReturnValue(otherSid);
    const second = await ready({ ...options, anchorShipmentId: 143 });
    expect(second.result.current.snapshot).toBeNull();
    expect(second.result.current.binding?.shipmentId).toBe(143);
    expect(api.getScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.createScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.createScenarioSession).toHaveBeenCalledWith('token', otherSid, { anchorShipmentId: 143 }, { signal: expect.any(AbortSignal) });
    expect(sessionStorage.getItem(hintKey)).toBe(sid);
    expect(sessionStorage.getItem('drift.scenario.v1:7:11:143')).toBe(otherSid);
  });
  it('ignores malformed UUID hints', async () => {
    sessionStorage.setItem(hintKey, '{"token":"untrusted"}');
    await ready();
    expect(api.createScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.getScenarioSession).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(hintKey)).toBe(sid);
  });
  it('continues in memory when tab storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('Denied', 'SecurityError'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Denied', 'SecurityError'); });
    const { result } = await ready();
    await act(() => result.current.refresh());
    expect(result.current.status).toBe('ready');
    expect(api.createScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.getScenarioSession).toHaveBeenCalledWith('token', sid, expect.anything());
  });
  it('replaces an expired or missing session hint once without replaying a command', async () => {
    sessionStorage.setItem(hintKey, sid);
    vi.mocked(api.getScenarioSession).mockRejectedValueOnce(problem(404, 'SCENARIO_NOT_FOUND'));
    vi.mocked(api.createScenarioSessionId).mockReturnValue(otherSid);
    const { result } = await ready();
    expect(api.createScenarioSession).toHaveBeenCalledExactlyOnceWith('token', otherSid, { anchorShipmentId: 142 }, expect.anything());
    expect(result.current.session?.sessionId).toBe(otherSid);
    expect(sessionStorage.getItem(hintKey)).toBe(otherSid);
    expect(api.startScenarioSession).not.toHaveBeenCalled();
  });
  it('does not loop if fresh creation returns a concealed 404', async () => {
    vi.mocked(api.createScenarioSession).mockRejectedValue(problem(404, 'SCENARIO_NOT_FOUND'));
    const { result } = renderHook(useScenarioSnapshot, { initialProps: options });
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.session).toBeNull();
    expect(api.createScenarioSession).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(hintKey)).toBeNull();
  });
  it('reuses the creation UUID after a lost PUT response', async () => {
    vi.mocked(api.createScenarioSession).mockRejectedValueOnce(new api.ScenarioApiError('Lost PUT', 0, 'network', null, false, true));
    const { result } = renderHook(useScenarioSnapshot, { initialProps: options });
    await waitFor(() => expect(result.current.status).toBe('error'));
    await act(() => result.current.refresh());
    expect(api.createScenarioSessionId).toHaveBeenCalledTimes(1);
    expect(api.createScenarioSession).toHaveBeenCalledTimes(2);
    expect(api.createScenarioSession).toHaveBeenLastCalledWith('token', sid, { anchorShipmentId: 142 }, expect.anything());
  });
  it('is idle without a viewer, company or valid route anchor', () => {
    const { result, rerender } = renderHook(useScenarioSnapshot, { initialProps: { ...options, viewer: null } });
    expect(result.current.status).toBe('idle');
    rerender({ ...options, viewer: null, anchorShipmentId: null });
    expect(api.createScenarioSession).not.toHaveBeenCalled();
  });
});

describe('explicit commands and capabilities', () => {
  it('publishes backend Baseline, Change and Worsened envelopes without computing facts', async () => {
    const { result } = await ready();
    await act(() => result.current.start());
    expect(result.current.session).toEqual(envelope('BASELINE'));
    await act(() => result.current.next());
    expect(result.current.session).toEqual(envelope('CHANGE'));
    await act(() => result.current.next());
    expect(result.current.session).toEqual(envelope('WORSENED'));
    await act(() => result.current.next());
    expect(api.advanceScenarioSession).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.advanceScenarioSession).mock.calls.map(call => call[2].request.expectedRevision)).toEqual([1, 2]);
  });
  it('resets through GET confirmation to UNSTARTED in the new epoch', async () => {
    server = envelope('WORSENED');
    const { result } = await ready();
    await act(() => result.current.reset());
    expect(result.current.session).toEqual(envelope(undefined, newEpoch));
    expect(result.current.snapshot).toBeNull();
    expect(api.getScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.resetScenarioSession).toHaveBeenCalledWith('token', sid,
      expect.objectContaining({ request: { expectedEpochId: epoch, expectedRevision: 3 } }), expect.anything());
  });
  it('locks synchronously against double-clicks and competing actions', async () => {
    const response = deferred<api.ScenarioSessionEnvelope>();
    vi.mocked(api.startScenarioSession).mockReturnValueOnce(response.promise);
    const { result } = await ready();
    let first!: Promise<void>;
    act(() => { first = result.current.start(); void result.current.start(); void result.current.next(); void result.current.reset(); });
    expect(result.current.busy).toBe(true);
    expect(result.current.snapshot).toBeNull();
    expect(api.startScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.advanceScenarioSession).not.toHaveBeenCalled();
    expect(api.resetScenarioSession).not.toHaveBeenCalled();
    await act(async () => { response.resolve(envelope('BASELINE')); await first; });
    expect(result.current.snapshot?.stage).toBe('BASELINE');
  });
  it('gates on backend capabilities without inferring permission from lifecycle', async () => {
    server = { ...envelope(), capabilities: { canRead: true, canStart: false, canAdvance: false, canReset: false, canGenerateAssessment: false } };
    const { result } = await ready();
    await act(async () => { await result.current.start(); await result.current.next(); await result.current.reset(); });
    expect(api.startScenarioSession).not.toHaveBeenCalled();
    expect(api.advanceScenarioSession).not.toHaveBeenCalled();
    expect(api.resetScenarioSession).not.toHaveBeenCalled();
  });
  it('reconciles stale context without automatically retrying the rejected command', async () => {
    const { result } = await ready();
    await act(() => result.current.start());
    server = envelope('CHANGE');
    vi.mocked(api.advanceScenarioSession).mockRejectedValueOnce(problem(409, 'STALE_REVISION'));
    await act(() => result.current.next());
    expect(result.current.session).toEqual(envelope('CHANGE'));
    expect(result.current.error?.code).toBe('STALE_REVISION');
    expect(result.current.pendingAction).toBeNull();
    expect(api.advanceScenarioSession).toHaveBeenCalledTimes(1);
    await act(() => result.current.next());
    const calls = vi.mocked(api.advanceScenarioSession).mock.calls;
    expect(calls[1][2].request.expectedRevision).toBe(2);
    expect(calls[1][2].idempotencyKey).not.toBe(calls[0][2].idempotencyKey);
  });
  it('reconciles STALE_EPOCH and adopts only the current GET epoch', async () => {
    server = envelope('BASELINE');
    const { result } = await ready();
    vi.mocked(api.advanceScenarioSession).mockRejectedValueOnce(problem(409, 'STALE_EPOCH'));
    server = envelope(undefined, newEpoch);
    await act(() => result.current.next());
    expect(result.current.session?.epochId).toBe(newEpoch);
    expect(result.current.snapshot).toBeNull();
  });
});

describe('unconfirmed outcomes and old replays', () => {
  it('GETs after an unknown outcome, blocks new actions and retries the identical key/body', async () => {
    vi.mocked(api.startScenarioSession).mockRejectedValueOnce(new api.ScenarioApiError('Lost reply', 0, 'network', null, false, true));
    const { result } = await ready();
    await act(() => result.current.start());
    expect(result.current.status).toBe('unconfirmed');
    expect(result.current.canRetryCommand).toBe(true);
    expect(api.getScenarioSession).toHaveBeenCalledTimes(1);
    await act(async () => { await result.current.start(); await result.current.next(); await result.current.reset(); });
    expect(api.startScenarioSession).toHaveBeenCalledTimes(1);
    await act(() => result.current.retryCommand());
    const calls = vi.mocked(api.startScenarioSession).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[1][2]).toBe(calls[0][2]);
    expect(Object.isFrozen(calls[0][2].request)).toBe(true);
    expect(result.current.status).toBe('ready');
    expect(result.current.pendingAction).toBeNull();
    expect(api.getScenarioSession).toHaveBeenCalledTimes(2);
  });
  it('cannot replay a command until a failed reconciliation GET succeeds', async () => {
    const offline = new api.ScenarioApiError('Offline', 0, 'network', null, false, true);
    const { result } = await ready();
    vi.mocked(api.startScenarioSession).mockRejectedValueOnce(offline);
    vi.mocked(api.getScenarioSession).mockRejectedValueOnce(offline).mockRejectedValueOnce(offline);
    await act(() => result.current.start());
    expect(result.current.pendingAction).toBe('start');
    await act(() => result.current.retryCommand());
    expect(api.startScenarioSession).toHaveBeenCalledTimes(1);
    await act(() => result.current.retryCommand());
    expect(api.startScenarioSession).toHaveBeenCalledTimes(2);
    expect(result.current.status).toBe('ready');
  });
  it('never displays an old-epoch command replay after an externally reset GET', async () => {
    server = envelope('BASELINE');
    const { result } = await ready();
    vi.mocked(api.advanceScenarioSession).mockRejectedValueOnce(new api.ScenarioApiError('Lost', 0, 'network', null, false, true))
      .mockResolvedValueOnce(envelope('CHANGE'));
    server = envelope(undefined, newEpoch);
    await act(() => result.current.next());
    expect(result.current.session?.epochId).toBe(newEpoch);
    await act(() => result.current.retryCommand());
    expect(result.current.session).toEqual(envelope(undefined, newEpoch));
    expect(result.current.snapshot).toBeNull();
  });
  it('does not promote a same-epoch higher-revision replay before its follow-up GET', async () => {
    const { result } = await ready();
    vi.mocked(api.startScenarioSession).mockRejectedValueOnce(new api.ScenarioApiError('Lost', 0, 'network', null, false, true))
      .mockResolvedValueOnce(envelope('BASELINE'));
    await act(() => result.current.start()); // GET saw revision zero, before the original command committed.
    const latest = deferred<api.ScenarioSessionEnvelope>();
    vi.mocked(api.getScenarioSession).mockReturnValueOnce(latest.promise);
    let retry!: Promise<void>;
    await act(async () => { retry = result.current.retryCommand(); await Promise.resolve(); });
    expect(result.current.snapshot).toBeNull();
    expect(result.current.busy).toBe(true);
    await act(async () => { latest.resolve(envelope(undefined, newEpoch)); await retry; });
    expect(result.current.session?.epochId).toBe(newEpoch);
    expect(result.current.snapshot).toBeNull();
  });
  it('keeps a newer accepted snapshot when an old same-epoch command is replayed', async () => {
    const { result } = await ready();
    vi.mocked(api.startScenarioSession).mockRejectedValueOnce(new api.ScenarioApiError('Lost', 0, 'network', null, false, true))
      .mockResolvedValueOnce(envelope('BASELINE'));
    server = envelope('WORSENED');
    await act(() => result.current.start());
    await act(() => result.current.retryCommand());
    expect(result.current.session).toEqual(envelope('WORSENED'));
  });
});

describe('request and context fences', () => {
  it('discards an old refresh released after Reset even when cancellation is ignored', async () => {
    server = envelope('BASELINE');
    const { result } = await ready();
    const oldRead = deferred<api.ScenarioSessionEnvelope>();
    vi.mocked(api.getScenarioSession).mockReturnValueOnce(oldRead.promise);
    let refresh!: Promise<void>;
    act(() => { refresh = result.current.refresh(); });
    const signal = vi.mocked(api.getScenarioSession).mock.calls[0][2]?.signal;
    await act(() => result.current.reset());
    expect(result.current.session?.epochId).toBe(newEpoch);
    expect(signal?.aborted).toBe(true);
    await act(async () => { oldRead.resolve(envelope('BASELINE')); await refresh; });
    expect(result.current.session).toEqual(envelope(undefined, newEpoch));
  });
  it('rejects lower GET revisions and retired GET epochs', async () => {
    server = envelope('WORSENED');
    const { result } = await ready();
    vi.mocked(api.getScenarioSession).mockResolvedValueOnce(envelope('BASELINE'));
    await act(() => result.current.refresh());
    expect(result.current.snapshot?.stage).toBe('WORSENED');
    expect(result.current.status).toBe('error');
    server = envelope(undefined, newEpoch);
    await act(() => result.current.refresh());
    expect(result.current.session?.epochId).toBe(newEpoch);
    vi.mocked(api.getScenarioSession).mockResolvedValueOnce(envelope('WORSENED'));
    await act(() => result.current.refresh());
    expect(result.current.session?.epochId).toBe(newEpoch);
    expect(result.current.snapshot).toBeNull();
    expect(result.current.status).toBe('error');
  });
  const switches: [string, UseScenarioSnapshotOptions][] = [
    ['account', { ...options, viewer: { ...options.viewer!, id: 8, token: 'other-token' } }],
    ['company', { ...options, viewer: { ...options.viewer!, company: { id: 12, code: 'OTHER', name: 'Other' } } }],
    ['shipment', { ...options, anchorShipmentId: 143 }],
    ['token', { ...options, viewer: { ...options.viewer!, token: 'renewed-token' } }],
    ['logout', { ...options, viewer: null }],
  ];
  it.each(switches)('discards a late creation after %s switch', async (_, nextProps) => {
    const old = deferred<api.ScenarioSessionEnvelope>();
    const replacement = deferred<api.ScenarioSessionEnvelope>();
    vi.mocked(api.createScenarioSession).mockReturnValueOnce(old.promise).mockReturnValueOnce(replacement.promise);
    vi.mocked(api.getScenarioSession).mockReturnValueOnce(replacement.promise);
    vi.mocked(api.createScenarioSessionId).mockReturnValueOnce(sid).mockReturnValue(otherSid);
    const { result, rerender } = renderHook(useScenarioSnapshot, { initialProps: options });
    const oldCallback = result.current.start;
    rerender(nextProps);
    expect(result.current.session).toBeNull();
    await act(async () => { old.resolve(envelope('WORSENED')); await old.promise; });
    expect(result.current.snapshot).toBeNull();
    await act(() => oldCallback());
    expect(api.startScenarioSession).not.toHaveBeenCalled();
    if (nextProps.viewer) {
      const id = nextProps.viewer.id !== 7 || nextProps.viewer.company?.id !== 11
        || nextProps.anchorShipmentId !== options.anchorShipmentId ? otherSid : sid;
      await act(async () => { replacement.resolve(envelope(undefined, epoch, id)); await replacement.promise; });
      expect(result.current.session?.state).toBe('UNSTARTED');
      expect(result.current.binding?.shipmentId).toBe(nextProps.anchorShipmentId);
    } else {
      expect(result.current.status).toBe('idle');
      expect(sessionStorage.getItem(hintKey)).toBeNull();
    }
  });
  it.each(switches)('discards a late command result after %s switch', async (_, nextProps) => {
    const { result, rerender } = await ready();
    const old = deferred<api.ScenarioSessionEnvelope>();
    const replacement = deferred<api.ScenarioSessionEnvelope>();
    vi.mocked(api.startScenarioSession).mockReturnValueOnce(old.promise);
    vi.mocked(api.createScenarioSession).mockReturnValueOnce(replacement.promise);
    vi.mocked(api.getScenarioSession).mockReturnValueOnce(replacement.promise);
    vi.mocked(api.createScenarioSessionId).mockReturnValue(otherSid);
    let pending!: Promise<void>;
    act(() => { pending = result.current.start(); });
    rerender(nextProps);
    expect(result.current.session).toBeNull();
    await act(async () => { old.resolve(envelope('WORSENED')); await pending; });
    expect(result.current.snapshot).toBeNull();
    if (nextProps.viewer) {
      const id = nextProps.viewer.id !== 7 || nextProps.viewer.company?.id !== 11
        || nextProps.anchorShipmentId !== options.anchorShipmentId ? otherSid : sid;
      await act(async () => { replacement.resolve(envelope(undefined, epoch, id)); await replacement.promise; });
      expect(result.current.session?.state).toBe('UNSTARTED');
    } else expect(result.current.status).toBe('idle');
  });
  it('fences A→B→A and StrictMode effect replays', async () => {
    const old = deferred<api.ScenarioSessionEnvelope>();
    vi.mocked(api.createScenarioSession).mockReturnValueOnce(old.promise);
    vi.mocked(api.createScenarioSessionId).mockReturnValueOnce(sid).mockReturnValue(otherSid);
    const { result, rerender } = renderHook(useScenarioSnapshot, { initialProps: options, reactStrictMode: true });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    rerender({ ...options, anchorShipmentId: 143 });
    rerender(options);
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(async () => { old.resolve(envelope('WORSENED')); await old.promise; });
    expect(result.current.session?.state).toBe('UNSTARTED');
    expect(result.current.binding?.scenarioShipmentKey).toBe('A');
    expect(sessionStorage.getItem(hintKey)).toBe(sid);
    expect(sessionStorage.getItem('drift.scenario.v1:7:11:143')).toBe(otherSid);
  });
});

describe('denial, unavailable states and request isolation', () => {
  it.each([[401, 'UNAUTHENTICATED'], [403, 'SCENARIO_ACCESS_DENIED'], [404, 'SCENARIO_DISABLED']] as const)(
    'clears protected state and hint on %i / %s', async (status, code) => {
      server = envelope('BASELINE');
      const { result } = await ready();
      vi.mocked(api.getScenarioSession).mockRejectedValueOnce(problem(status, code));
      await act(() => result.current.refresh());
      expect(result.current.session).toBeNull();
      expect(result.current.snapshot).toBeNull();
      expect(result.current.error?.code).toBe(code);
      expect(sessionStorage.getItem(hintKey)).toBeNull();
      await act(async () => { await result.current.refresh(); await result.current.next(); });
      expect(api.getScenarioSession).toHaveBeenCalledTimes(1);
      expect(api.advanceScenarioSession).not.toHaveBeenCalled();
    });
  it('clears state on command authorization loss without a recovery GET', async () => {
    const { result } = await ready();
    vi.mocked(api.startScenarioSession).mockRejectedValueOnce(problem(403, 'SCENARIO_ACCESS_DENIED'));
    await act(() => result.current.start());
    expect(result.current.session).toBeNull();
    expect(result.current.pendingAction).toBeNull();
    expect(api.getScenarioSession).not.toHaveBeenCalled();
  });
  it('does not silently create another session after an unconfirmed command expires', async () => {
    const { result } = await ready();
    vi.mocked(api.startScenarioSession).mockRejectedValueOnce(new api.ScenarioApiError('Lost', 0, 'network', null, false, true));
    vi.mocked(api.getScenarioSession).mockRejectedValueOnce(problem(404, 'SCENARIO_NOT_FOUND'));
    await act(() => result.current.start());
    expect(result.current.session).toBeNull();
    expect(result.current.status).toBe('error');
    expect(api.createScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.startScenarioSession).toHaveBeenCalledTimes(1);
  });
  it('reports missing configuration and does not issue commands', async () => {
    vi.mocked(api.createScenarioSession).mockRejectedValueOnce(problem(503, 'SCENARIO_NOT_CONFIGURED'));
    const { result } = renderHook(useScenarioSnapshot, { initialProps: options });
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error?.code).toBe('SCENARIO_NOT_CONFIGURED');
    expect(result.current.session).toBeNull();
    await act(() => result.current.start());
    expect(api.startScenarioSession).not.toHaveBeenCalled();
  });
  it.each(['canRead', 'anchor'] as const)('rejects a recovered session with invalid %s authority', async reason => {
    server = reason === 'canRead' ? { ...envelope('BASELINE'), capabilities: { ...envelope().capabilities, canRead: false } } : envelope('BASELINE');
    const { result } = renderHook(useScenarioSnapshot, { initialProps: { ...options, anchorShipmentId: reason === 'anchor' ? 999 : 142 } });
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.session).toBeNull();
    expect(result.current.error?.status).toBe(403);
  });
  it('makes no background tracking, AI, provider or polling requests', async () => {
    const { result } = await ready();
    vi.useFakeTimers();
    await act(() => vi.advanceTimersByTimeAsync(120000));
    expect(result.current.session?.state).toBe('UNSTARTED');
    expect(api.createScenarioSession).toHaveBeenCalledTimes(1);
    expect(api.getScenarioSession).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });
});
