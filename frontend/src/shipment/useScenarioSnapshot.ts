import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { Session } from '../session/session';
import {
  advanceScenarioSession, compareScenarioVersions, createScenarioCommand, createScenarioSession,
  createScenarioSessionId, getScenarioSession, resetScenarioSession, ScenarioApiError, startScenarioSession,
  type ScenarioCommand, type ScenarioSessionEnvelope, type ScenarioShipmentBinding, type ScenarioSnapshot,
} from './scenarioApi';

export type ScenarioAction = 'start' | 'next' | 'reset';
export type ScenarioCoordinatorStatus = 'idle' | 'loading' | 'refreshing' | 'ready' | 'command' | 'reconciling' | 'unconfirmed' | 'error';
export type UseScenarioSnapshotOptions = Readonly<{
  /** Pass null on logout. Authentication and routing remain owned by the host. */
  viewer: Pick<Session, 'id' | 'token' | 'company'> | null;
  anchorShipmentId: number | null;
}>;
export type ScenarioSnapshotController = Readonly<{
  session: ScenarioSessionEnvelope | null;
  snapshot: ScenarioSnapshot | null;
  binding: ScenarioShipmentBinding | null;
  status: ScenarioCoordinatorStatus;
  busy: boolean;
  error: ScenarioApiError | null;
  pendingAction: ScenarioAction | null;
  canRetryCommand: boolean;
  start: () => Promise<void>;
  next: () => Promise<void>;
  reset: () => Promise<void>;
  refresh: () => Promise<void>;
  retryCommand: () => Promise<void>;
}>;

type Scope = { token: string | undefined; userId: number | undefined; companyId: number | undefined; anchor: number | null };
type Pending = { action: ScenarioAction; sessionId: string; command: ScenarioCommand; reconciled: boolean };
type View = { owner: Scope; session: ScenarioSessionEnvelope | null; status: ScenarioCoordinatorStatus;
  error: ScenarioApiError | null; pendingAction: ScenarioAction | null; locked: boolean };
type Runtime = {
  scope: Scope; storageKey: string; active: boolean; generation: number; abort: AbortController | null;
  sessionId: string | null; creating: boolean; session: ScenarioSessionEnvelope | null;
  pending: Pending | null; locked: boolean; needsRead: boolean; blocked: boolean; retiredEpochs: Set<string>;
  status: ScenarioCoordinatorStatus; error: ScenarioApiError | null;
  live: () => boolean; emit: () => void;
};
type Ticket = { generation: number; signal: AbortSignal };
const commands = { start: startScenarioSession, next: advanceScenarioSession, reset: resetScenarioSession };
const capability = { start: 'canStart', next: 'canAdvance', reset: 'canReset' } as const;
const busy = (status: ScenarioCoordinatorStatus) => ['loading', 'refreshing', 'command', 'reconciling'].includes(status);

function readHint(key: string): string | null {
  try {
    const value = sessionStorage.getItem(key);
    return value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) ? value : null;
  } catch { return null; }
}
function writeHint(key: string, value: string | null) {
  try {
    if (value === null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, value);
  } catch { /* Storage denial must not prevent an in-memory session. Never persist facts or tokens. */ }
}
function current(rt: Runtime, ticket: Ticket): boolean {
  return rt.live() && rt.generation === ticket.generation;
}
function publish(rt: Runtime, status: ScenarioCoordinatorStatus, error: ScenarioApiError | null = null) {
  rt.status = status;
  rt.error = error;
  rt.emit();
}
function begin(rt: Runtime, status: ScenarioCoordinatorStatus): Ticket {
  rt.generation += 1;
  rt.abort?.abort();
  rt.abort = new AbortController();
  publish(rt, status, rt.pending ? rt.error : null);
  return { generation: rt.generation, signal: rt.abort.signal };
}
function asError(error: unknown, outcomeUnknown = false): ScenarioApiError {
  return error instanceof ScenarioApiError ? error
    : new ScenarioApiError('The scenario request could not be completed.', 0, 'network', null, false, outcomeUnknown);
}
function missing(error: ScenarioApiError): boolean {
  return error.status === 404 && error.code !== 'SCENARIO_DISABLED';
}
function fail(rt: Runtime, error: ScenarioApiError) {
  rt.needsRead = true;
  if ([401, 403, 404].includes(error.status)) {
    rt.session = null;
    rt.sessionId = null;
    rt.pending = null;
    rt.creating = false;
    writeHint(rt.storageKey, null);
    rt.blocked = error.status !== 404 || error.code === 'SCENARIO_DISABLED';
  }
  publish(rt, 'error', error);
}
function validateContext(rt: Runtime, envelope: ScenarioSessionEnvelope) {
  if (envelope.sessionId !== rt.sessionId) {
    throw new ScenarioApiError('The scenario response belongs to another session.', 502, 'malformed-response');
  }
  if (!envelope.capabilities.canRead || !envelope.shipments.some(binding => binding.shipmentId === rt.scope.anchor)) {
    throw new ScenarioApiError('This scenario is not authorized for the selected shipment.', 403, 'http');
  }
}
/** Only a GET issued in the current request generation may authorize a new epoch.
 * Retired epochs and lower revisions can never become current again. */
function acceptRead(rt: Runtime, envelope: ScenarioSessionEnvelope) {
  validateContext(rt, envelope);
  if (rt.retiredEpochs.has(envelope.epochId)
    || (rt.session && compareScenarioVersions(rt.session, envelope) === 'older')) {
    throw new ScenarioApiError('The response predates the confirmed scenario. Reload the session.', 502, 'malformed-response');
  }
  if (rt.session && rt.session.epochId !== envelope.epochId) rt.retiredEpochs.add(rt.session.epochId);
  rt.session = envelope;
  rt.creating = false;
  rt.needsRead = false;
  if (rt.pending) rt.pending.reconciled = true;
}
function newSession(rt: Runtime) {
  rt.session = null;
  rt.sessionId = createScenarioSessionId();
  rt.creating = true;
  rt.retiredEpochs.clear();
  writeHint(rt.storageKey, rt.sessionId);
}
async function read(rt: Runtime, ticket: Ticket, recoverMissing: boolean, notice: ScenarioApiError | null = null): Promise<boolean> {
  try {
    if (!rt.sessionId) newSession(rt);
    let envelope: ScenarioSessionEnvelope;
    try {
      envelope = rt.creating
        ? await createScenarioSession(rt.scope.token!, rt.sessionId!, { anchorShipmentId: rt.scope.anchor! }, { signal: ticket.signal })
        : await getScenarioSession(rt.scope.token!, rt.sessionId!, { signal: ticket.signal });
    } catch (error) {
      if (!current(rt, ticket)) return false;
      if (rt.creating || !recoverMissing || !missing(asError(error))) throw error;
      // One recovery attempt, never a command replay or a loop after a denied PUT.
      newSession(rt);
      publish(rt, 'loading');
      envelope = await createScenarioSession(rt.scope.token!, rt.sessionId!, { anchorShipmentId: rt.scope.anchor! }, { signal: ticket.signal });
    }
    if (!current(rt, ticket)) return false;
    acceptRead(rt, envelope);
    publish(rt, rt.pending ? 'unconfirmed' : 'ready', notice);
    return true;
  } catch (error) {
    if (current(rt, ticket)) fail(rt, asError(error));
    return false;
  }
}
async function execute(rt: Runtime, ticket: Ticket, pending: Pending, replay = false) {
  try {
    const result = await commands[pending.action](rt.scope.token!, pending.sessionId, pending.command, { signal: ticket.signal });
    if (!current(rt, ticket)) return;
    validateContext(rt, result);
    const comparison = rt.session ? compareScenarioVersions(rt.session, result) : 'different-epoch';
    rt.pending = null; // A definitive replay response resolves the original command, even if old.
    if (replay || comparison === 'different-epoch' || comparison === 'older' || comparison === 'different-session') {
      rt.needsRead = true;
      publish(rt, 'reconciling');
      await read(rt, ticket, false);
    } else {
      if (comparison === 'newer') rt.session = result;
      rt.needsRead = false;
      publish(rt, 'ready');
    }
  } catch (error) {
    if (!current(rt, ticket)) return;
    const problem = asError(error, true);
    if (problem.outcomeUnknown) {
      pending.reconciled = false;
      rt.needsRead = true;
      publish(rt, 'reconciling', problem);
      // GET establishes current state, but cannot identify which command committed.
      // Retain the exact key/body until explicit replay resolves its outcome.
      await read(rt, ticket, false, problem);
    } else if (problem.status === 409) {
      rt.pending = null;
      rt.needsRead = true;
      publish(rt, 'reconciling', problem);
      await read(rt, ticket, false, problem);
    } else {
      rt.pending = null;
      fail(rt, problem);
    }
  }
}
async function refresh(rt: Runtime) {
  if (rt.blocked || rt.locked) return;
  const notice = rt.pending ? rt.error : null;
  rt.locked = !!rt.pending;
  const ticket = begin(rt, rt.pending ? 'reconciling' : rt.session ? 'refreshing' : 'loading');
  try { await read(rt, ticket, !rt.pending, notice); }
  finally { if (current(rt, ticket)) { rt.locked = false; rt.emit(); } }
}
async function act(rt: Runtime, action: ScenarioAction) {
  if (rt.blocked || rt.locked || rt.pending || rt.needsRead || !rt.session
    || !rt.session.capabilities.canRead || !rt.session.capabilities[capability[action]]) return;
  const pending: Pending = { action, sessionId: rt.session.sessionId,
    command: createScenarioCommand(rt.session, crypto.randomUUID()), reconciled: true };
  rt.pending = pending; // Lock synchronously, before React renders or the first await.
  rt.locked = true;
  const ticket = begin(rt, 'command');
  try { await execute(rt, ticket, pending); }
  finally { if (current(rt, ticket)) { rt.locked = false; rt.emit(); } }
}
async function retry(rt: Runtime) {
  const pending = rt.pending;
  if (!pending || rt.blocked || rt.locked || busy(rt.status)) return;
  rt.locked = true;
  const ticket = begin(rt, 'reconciling');
  try {
    if ((!pending.reconciled || rt.needsRead) && !await read(rt, ticket, false, rt.error)) return;
    if (!current(rt, ticket) || rt.pending !== pending) return;
    publish(rt, 'command');
    // Even a same-epoch replay with a greater revision can predate an external Reset.
    // Resolve the key, then GET; never promote a replay based on arrival or revision alone.
    await execute(rt, ticket, pending, true);
  } finally { if (current(rt, ticket)) { rt.locked = false; rt.emit(); } }
}

/** One owner per mounted hook. No polling, shipment/track reads or assessment calls.
 * The host supplies live viewer/route props and consumes session/snapshot atomically.
 * Cleanup aborts and fences work even if a transport ignores AbortSignal. */
export function useScenarioSnapshot({ viewer, anchorShipmentId }: UseScenarioSnapshotOptions): ScenarioSnapshotController {
  const scope = useMemo<Scope>(() => ({ token: viewer?.token, userId: viewer?.id,
    companyId: viewer?.company?.id, anchor: anchorShipmentId }),
  [viewer?.token, viewer?.id, viewer?.company?.id, anchorShipmentId]);
  const runtime = useRef<Runtime | null>(null);
  const previousStorageKey = useRef<string | null>(null);
  const [view, setView] = useState<View | null>(null);

  useLayoutEffect(() => {
    const storageKey = scope.userId && scope.companyId
      ? `drift.scenario.v1:${scope.userId}:${scope.companyId}` : null;
    if (previousStorageKey.current && previousStorageKey.current !== storageKey) writeHint(previousStorageKey.current, null);
    previousStorageKey.current = storageKey;
    if (!storageKey || !scope.anchor || !Number.isSafeInteger(scope.anchor) || scope.anchor <= 0) {
      runtime.current = null;
      return;
    }
    const rt: Runtime = {
      scope, storageKey, active: true, generation: 0, abort: null,
      sessionId: readHint(storageKey), creating: false, session: null, pending: null, locked: false,
      needsRead: false, blocked: false, retiredEpochs: new Set(), status: 'loading', error: null,
      live: () => rt.active && runtime.current === rt,
      emit: () => { if (rt.live()) setView({ owner: scope, session: rt.session, status: rt.status,
        error: rt.error, pendingAction: rt.pending?.action ?? null, locked: rt.locked }); },
    };
    runtime.current = rt;
    const ticket = begin(rt, 'loading');
    void read(rt, ticket, true);
    return () => { rt.active = false; rt.generation += 1; rt.abort?.abort(); };
  }, [scope]);

  const invoke = useCallback(async (action: ScenarioAction | 'refresh' | 'retry') => {
    const rt = runtime.current;
    if (!rt?.live() || rt.scope !== scope) return;
    if (action === 'refresh') await refresh(rt);
    else if (action === 'retry') await retry(rt);
    else await act(rt, action);
  }, [scope]);
  // Mask the old owner during render, before effect cleanup. This also fences A→B→A.
  const owned = view?.owner === scope ? view : null;
  const session = owned?.session ?? null;
  const status = owned?.status ?? 'idle';
  return {
    session, snapshot: session?.snapshot ?? null,
    binding: session?.shipments.find(binding => binding.shipmentId === anchorShipmentId) ?? null,
    status, busy: busy(status) || !!owned?.locked, error: owned?.error ?? null, pendingAction: owned?.pendingAction ?? null,
    canRetryCommand: !!owned?.pendingAction && !busy(status) && !owned.locked,
    start: () => invoke('start'), next: () => invoke('next'), reset: () => invoke('reset'),
    refresh: () => invoke('refresh'), retryCommand: () => invoke('retry'),
  };
}
