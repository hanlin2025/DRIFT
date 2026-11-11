import { ApiError } from '../signup/api';
import type { TrackVessel } from './api';

export type ScenarioShipmentKey = 'A' | 'B';
export type ScenarioStage = 'BASELINE' | 'CHANGE' | 'WORSENED';
export type ScenarioLifecycleState = 'UNSTARTED' | 'ACTIVE' | 'COMPLETE';

/** Transport-only domain facts. Existing domain parsers must validate these before rendering.
 * Do not cast them to ShipmentRisk, MaritimeContext, etc. or calculate replacement facts. */
export type ScenarioDomainPayload = Readonly<Record<string, unknown>>;

export type ScenarioCapabilities = Readonly<{
  canRead: boolean;
  canStart: boolean;
  canAdvance: boolean;
  canReset: boolean;
  canGenerateAssessment: false;
}>;

export type ScenarioShipmentBinding = Readonly<{
  scenarioShipmentKey: ScenarioShipmentKey;
  /** Persistent, authorized navigation anchor. */
  shipmentId: number;
  shipmentReference: string;
  /** Fictional fixture reference; never use as an ordinary shipment identifier. */
  scenarioShipmentReference: string;
}>;

export type ScenarioShipmentRead = Readonly<{
  scenarioShipmentKey: ScenarioShipmentKey;
  /** Same persistent ID as the corresponding binding's shipmentId. */
  sourceShipmentId: number;
  scenarioShipmentReference: string;
  transshipmentPort: string;
  plannedMotherArrivalAt: string;
  plannedFeederDepartureAt: string;
  plannedConnectionWindow: ScenarioDomainPayload;
  remainingConnectionWindow: ScenarioDomainPayload;
  arrivalEstimate: ScenarioDomainPayload;
  risk: ScenarioDomainPayload;
  businessImpact: ScenarioDomainPayload;
}>;

export type ScenarioAisObservation = Readonly<{
  vesselRole: TrackVessel;
  observedAtUtc: string;
  receivedAtUtc: string;
  sourceMode: string;
  /** Original AIS fields, including ingestedAt/source and nullable readings, unchanged. */
  position: ScenarioDomainPayload;
}>;

export type ScenarioSnapshot = Readonly<{
  snapshotId: string;
  epochId: string;
  revision: number;
  stage: ScenarioStage;
  stageOrder: 0 | 1 | 2;
  stageCount: 3;
  fixtureStageId: string;
  simulationCutoffUtc: string;
  mapPlaybackCutoffUtc: string;
  shipments: readonly ScenarioShipmentRead[];
  aisObservations: readonly ScenarioAisObservation[];
  maritimeContext: ScenarioDomainPayload;
}>;

type ScenarioSessionMetadata = Readonly<{
  apiVersion: '1';
  sessionId: string;
  scenarioId: string;
  scenarioContractVersion: 'cdg279.synthetic-disruption.v1';
  epochId: string;
  revision: number;
  capabilities: ScenarioCapabilities;
  shipments: readonly ScenarioShipmentBinding[];
  emailNotificationStatus?: 'NOT_TRIGGERED' | 'SUBMISSION_PENDING' | 'SUBMITTING' | 'SUBMITTED' | 'FAILED' | 'PARTIAL_FAILURE' | 'DISABLED' | 'NO_APPROVED_RECIPIENTS';
}>;

export type ScenarioSessionEnvelope = ScenarioSessionMetadata & (
  | { readonly state: 'UNSTARTED'; readonly snapshot: null }
  | { readonly state: 'ACTIVE' | 'COMPLETE'; readonly snapshot: ScenarioSnapshot }
);

export type ScenarioCreateSessionRequest = Readonly<{ anchorShipmentId: number }>;
export type ScenarioCommandRequest = Readonly<{ expectedEpochId: string; expectedRevision: number }>;
export type ScenarioCommand = Readonly<{
  idempotencyKey: string;
  request: ScenarioCommandRequest;
}>;
export type ScenarioRequestOptions = Readonly<{ signal?: AbortSignal }>;
export type ScenarioVersion = Pick<ScenarioSessionEnvelope, 'sessionId' | 'epochId' | 'revision'>;
export type ScenarioVersionComparison = 'different-session' | 'different-epoch' | 'older' | 'equal' | 'newer';

/** Compare candidate against the coordinator's last accepted version, never arrival time.
 * Only revisions within the SAME session AND epoch are ordered. Different epochs are
 * opaque and require a fenced GET reconciliation, even for a successful Reset replay.
 * 'newer' is not permission to render: the future coordinator must also fence requests,
 * session/user ownership and invalidated epochs. 'equal' supplies no new current state. */
export function compareScenarioVersions(current: ScenarioVersion, candidate: ScenarioVersion): ScenarioVersionComparison {
  if (current.sessionId !== candidate.sessionId) return 'different-session';
  if (current.epochId !== candidate.epochId) return 'different-epoch';
  return candidate.revision < current.revision ? 'older' : candidate.revision > current.revision ? 'newer' : 'equal';
}

export function createScenarioSessionId(): string {
  return crypto.randomUUID();
}

/** Prepare once per user intent and retain this object after an unknown outcome.
 * Retry the same action/session with this exact key/body. Never silently refresh its
 * expected revision or generate a new key. After a definitive conflict, reconcile via
 * GET before preparing a new command for a new user intent. */
export function createScenarioCommand(context: ScenarioVersion, idempotencyKey: string): ScenarioCommand {
  return Object.freeze({
    idempotencyKey,
    request: Object.freeze({ expectedEpochId: context.epochId, expectedRevision: context.revision }),
  });
}

export type ScenarioErrorCode =
  | 'INVALID_REQUEST' | 'UNAUTHENTICATED' | 'SCENARIO_ACCESS_DENIED'
  | 'SCENARIO_DISABLED' | 'SCENARIO_NOT_FOUND'
  | 'STALE_EPOCH' | 'STALE_REVISION' | 'SCENARIO_NOT_STARTED' | 'SCENARIO_COMPLETE'
  | 'SESSION_ID_REUSED' | 'IDEMPOTENCY_KEY_REUSED' | 'SESSION_COMMAND_LIMIT'
  | 'SCENARIO_NOT_CONFIGURED' | 'SESSION_CAPACITY';
export type ScenarioErrorKind = 'api' | 'http' | 'malformed-response' | 'network' | 'timeout' | 'cancelled';

const ERROR_CODES: readonly ScenarioErrorCode[] = [
  'INVALID_REQUEST', 'UNAUTHENTICATED', 'SCENARIO_ACCESS_DENIED', 'SCENARIO_DISABLED', 'SCENARIO_NOT_FOUND',
  'STALE_EPOCH', 'STALE_REVISION', 'SCENARIO_NOT_STARTED', 'SCENARIO_COMPLETE', 'SESSION_ID_REUSED',
  'IDEMPOTENCY_KEY_REUSED', 'SESSION_COMMAND_LIMIT', 'SCENARIO_NOT_CONFIGURED', 'SESSION_CAPACITY',
];

/** ApiError compatibility preserves the frontend's status/field error handling.
 * code is null for unrecognized or absent backend codes; backendCode retains new codes.
 * SCENARIO_NOT_FOUND intentionally covers missing, expired and concealed sessions.
 * retryable reports the server flag only; this adapter never retries any request. */
export class ScenarioApiError extends ApiError {
  readonly code: ScenarioErrorCode | null;
  constructor(
    message: string,
    status: number,
    readonly kind: ScenarioErrorKind,
    readonly backendCode: string | null = null,
    readonly retryable = false,
    readonly outcomeUnknown = false,
    fields: Record<string, string> = {},
  ) {
    super(message, status, fields);
    this.name = 'ScenarioApiError';
    this.code = ERROR_CODES.find(code => code === backendCode) ?? null;
  }

  get requiresReconciliation(): boolean {
    return this.outcomeUnknown || this.code === 'STALE_EPOCH' || this.code === 'STALE_REVISION';
  }
}

export function createScenarioSession(token: string, sessionId: string, request: ScenarioCreateSessionRequest,
  options: ScenarioRequestOptions = {}): Promise<ScenarioSessionEnvelope> {
  return sendSession(token, sessionId, '', 'PUT', { anchorShipmentId: request.anchorShipmentId }, options);
}

export function getScenarioSession(token: string, sessionId: string,
  options: ScenarioRequestOptions = {}): Promise<ScenarioSessionEnvelope> {
  return sendSession(token, sessionId, '', 'GET', undefined, options);
}

export function startScenarioSession(token: string, sessionId: string, command: ScenarioCommand,
  options: ScenarioRequestOptions = {}): Promise<ScenarioSessionEnvelope> {
  return sendCommand(token, sessionId, 'start', command, options);
}

export function advanceScenarioSession(token: string, sessionId: string, command: ScenarioCommand,
  options: ScenarioRequestOptions = {}): Promise<ScenarioSessionEnvelope> {
  return sendCommand(token, sessionId, 'next', command, options);
}

export function resetScenarioSession(token: string, sessionId: string, command: ScenarioCommand,
  options: ScenarioRequestOptions = {}): Promise<ScenarioSessionEnvelope> {
  return sendCommand(token, sessionId, 'reset', command, options);
}

function sendCommand(token: string, sessionId: string, action: 'start' | 'next' | 'reset',
  command: ScenarioCommand, options: ScenarioRequestOptions): Promise<ScenarioSessionEnvelope> {
  return sendSession(token, sessionId, `/${action}`, 'POST', {
    expectedEpochId: command.request.expectedEpochId,
    expectedRevision: command.request.expectedRevision,
  }, options, command.idempotencyKey);
}

const UNEXPECTED = 'DRIFT returned an unexpected scenario response.';

async function sendSession(token: string, sessionId: string, suffix: string, method: 'GET' | 'PUT' | 'POST',
  body: unknown, options: ScenarioRequestOptions, idempotencyKey?: string): Promise<ScenarioSessionEnvelope> {
  const controller = new AbortController();
  let timedOut = false;
  let dispatched = false;
  const cancel = () => controller.abort();
  options.signal?.addEventListener('abort', cancel, { once: true });
  if (options.signal?.aborted) controller.abort();
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 15000);
  const mutating = method !== 'GET';
  try {
    controller.signal.throwIfAborted();
    dispatched = true;
    const response = await fetch(`/api/scenarios/v1/sessions/${encodeURIComponent(sessionId)}${suffix}`, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : { 'X-DRIFT-Request': '1' }),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(idempotencyKey === undefined ? {} : { 'Idempotency-Key': idempotencyKey }),
      },
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      cache: 'no-store',
    });
    let data: unknown;
    try {
      data = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null;
    } catch {
      controller.signal.throwIfAborted();
      data = null;
    }
    controller.signal.throwIfAborted();
    if (!response.ok) {
      const apiError = isRecord(data) && isText(data.code) && isText(data.message) && typeof data.retryable === 'boolean'
        ? { code: data.code, message: data.message, retryable: data.retryable } : null;
      const fields: Record<string, string> = {};
      if (isRecord(data) && isRecord(data.fieldErrors)) {
        for (const [key, value] of Object.entries(data.fieldErrors)) if (typeof value === 'string') fields[key] = value;
      }
      throw new ScenarioApiError(
        response.status === 401 ? 'Your session has ended. Log in again.' : apiError?.message ?? UNEXPECTED,
        response.status, apiError ? 'api' : 'http', apiError?.code ?? null,
        apiError?.retryable ?? false, mutating && response.status >= 500, fields,
      );
    }
    if (!isSession(data) || data.sessionId.toLowerCase() !== sessionId.toLowerCase()) {
      throw new ScenarioApiError(UNEXPECTED, 502, 'malformed-response', null, false, mutating);
    }
    return data;
  } catch (error) {
    if (error instanceof ScenarioApiError) throw error;
    if (controller.signal.aborted) {
      throw new ScenarioApiError(timedOut ? 'The scenario request timed out.' : 'The scenario request was cancelled.',
        0, timedOut ? 'timeout' : 'cancelled', null, false, mutating && dispatched);
    }
    if (error instanceof TypeError) {
      throw new ScenarioApiError('We could not reach DRIFT. Check your connection and try again.',
        0, 'network', null, false, mutating && dispatched);
    }
    throw error;
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', cancel);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
function isUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
function isRevision(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}
function isId(value: unknown): value is number {
  return isRevision(value) && value > 0;
}
function isInstant(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value));
}
function isKey(value: unknown): value is ScenarioShipmentKey {
  return value === 'A' || value === 'B';
}
function isBinding(value: unknown): value is ScenarioShipmentBinding {
  return isRecord(value) && isKey(value.scenarioShipmentKey) && isId(value.shipmentId)
    && isText(value.shipmentReference) && isText(value.scenarioShipmentReference);
}
function isShipmentRead(value: unknown): value is ScenarioShipmentRead {
  return isRecord(value) && isKey(value.scenarioShipmentKey) && isId(value.sourceShipmentId)
    && isText(value.scenarioShipmentReference) && isText(value.transshipmentPort)
    && isInstant(value.plannedMotherArrivalAt) && isInstant(value.plannedFeederDepartureAt)
    && ['plannedConnectionWindow', 'remainingConnectionWindow', 'arrivalEstimate', 'risk', 'businessImpact']
      .every(key => isRecord(value[key]));
}
function isObservation(value: unknown): value is ScenarioAisObservation {
  return isRecord(value) && (value.vesselRole === 'MOTHER' || value.vesselRole === 'FEEDER')
    && isInstant(value.observedAtUtc) && isInstant(value.receivedAtUtc) && isText(value.sourceMode) && isRecord(value.position);
}
function isSnapshot(value: unknown): value is ScenarioSnapshot {
  if (!isRecord(value) || !isUuid(value.snapshotId) || !isUuid(value.epochId) || !isRevision(value.revision)
    || !['BASELINE', 'CHANGE', 'WORSENED'].includes(value.stage as string)
    || ![0, 1, 2].includes(value.stageOrder as number) || value.stageCount !== 3 || !isText(value.fixtureStageId)
    || !isInstant(value.simulationCutoffUtc) || !isInstant(value.mapPlaybackCutoffUtc)
    || !Array.isArray(value.shipments) || value.shipments.length !== 2 || !value.shipments.every(isShipmentRead)
    || new Set(value.shipments.map(row => row.scenarioShipmentKey)).size !== 2
    || !Array.isArray(value.aisObservations) || value.aisObservations.length !== 2 || !value.aisObservations.every(isObservation)
    || new Set(value.aisObservations.map(row => row.vesselRole)).size !== 2 || !isRecord(value.maritimeContext)) return false;
  return true;
}
function isSession(value: unknown): value is ScenarioSessionEnvelope {
  if (!isRecord(value) || value.apiVersion !== '1' || value.scenarioContractVersion !== 'cdg279.synthetic-disruption.v1'
    || !isUuid(value.sessionId) || !isText(value.scenarioId) || !isUuid(value.epochId) || !isRevision(value.revision)
    || !isRecord(value.capabilities) || value.capabilities.canGenerateAssessment !== false
    || !['canRead', 'canStart', 'canAdvance', 'canReset'].every(key => typeof (value.capabilities as Record<string, unknown>)[key] === 'boolean')
    || !Array.isArray(value.shipments) || value.shipments.length !== 2 || !value.shipments.every(isBinding)
    || new Set(value.shipments.map(binding => binding.scenarioShipmentKey)).size !== 2
    || new Set(value.shipments.map(binding => binding.shipmentId)).size !== 2
    || (value.emailNotificationStatus !== undefined && !['NOT_TRIGGERED', 'SUBMISSION_PENDING', 'SUBMITTING', 'SUBMITTED', 'FAILED', 'PARTIAL_FAILURE', 'DISABLED', 'NO_APPROVED_RECIPIENTS'].includes(value.emailNotificationStatus as string))) return false;
  if (value.state === 'UNSTARTED') return value.snapshot === null && value.revision === 0;
  if (value.state !== 'ACTIVE' && value.state !== 'COMPLETE') return false;
  const snapshot = value.snapshot;
  if (!isSnapshot(snapshot) || snapshot.epochId !== value.epochId || snapshot.revision !== value.revision
    || snapshot.revision === 0 || (value.state === 'COMPLETE') !== (snapshot.stage === 'WORSENED')) return false;
  const bindings = value.shipments;
  return snapshot.shipments.every(row => bindings.some(binding => binding.scenarioShipmentKey === row.scenarioShipmentKey
    && binding.shipmentId === row.sourceShipmentId && binding.scenarioShipmentReference === row.scenarioShipmentReference));
}
