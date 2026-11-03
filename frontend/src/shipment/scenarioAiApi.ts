import { ApiError } from '../signup/api';
import type {
  MaritimeAiAssessment, MaritimeAiBusinessComparison, MaritimeAiBusinessContext,
  MaritimeAiBusinessDeadline, MaritimeAiClaim, MaritimeAiDeterministicSummary, MaritimeAiFact,
  MaritimeAiRelationshipType,
  MaritimeAiRiskReview, MaritimeAiRiskSnapshot,
} from './api';

export type ScenarioInterpretationRequest = Readonly<{
  scenarioShipmentKey: 'A' | 'B';
  expectedEpochId: string;
  expectedRevision: number;
  expectedSnapshotId: string;
  expectedSimulationCutoffUtc: string;
}>;

export type ScenarioInterpretationResponse = Readonly<{
  sessionId: string;
  epochId: string;
  snapshotId: string;
  revision: number;
  simulationCutoffUtc: string;
  scenarioShipmentKey: 'A' | 'B';
  shipmentId: number;
  assessment: MaritimeAiAssessment;
}>;

export class ScenarioAiApiError extends ApiError {
  constructor(message: string, status: number, readonly backendCode: string | null = null,
    readonly retryable = false) {
    super(message, status);
    this.name = 'ScenarioAiApiError';
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function text(value: unknown): value is string { return typeof value === 'string'; }
function nonempty(value: unknown): value is string { return text(value) && value.trim().length > 0; }
function nullableText(value: unknown): value is string | null { return value === null || text(value); }
function nullableFinite(value: unknown): value is number | null { return value === null || (typeof value === 'number' && Number.isFinite(value)); }
function stringList(value: unknown): value is string[] { return Array.isArray(value) && value.every(text); }
function uuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

const RELATIONSHIPS = ['SCHEDULE_REVIEW', 'PORT_CONTEXT_REVIEW', 'PORT_TIME_OVERLAP_REVIEW', 'REGIONAL_WEATHER_CONTEXT', 'RISK_BUFFER_REVIEW', 'DELAY_COMPARISON_REVIEW'] as const;
function isRelationship(value: unknown): value is MaritimeAiRelationshipType {
  return typeof value === 'string' && RELATIONSHIPS.some(relationship => relationship === value);
}
function parseRiskReview(value: unknown): MaritimeAiRiskReview | null | undefined {
  if (value === undefined) return undefined;
  if (value === null) return null;
  if (!record(value)) return undefined;
  const severity = value.severity;
  const basis = value.selectedWindowBasis;
  const window = value.selectedWindowState;
  const freshness = value.estimateFreshness;
  if (!(severity === null || severity === 'LOW' || severity === 'MEDIUM' || severity === 'HIGH' || severity === 'CRITICAL')
    || !(basis === null || basis === 'PLANNED_SHIPMENT_SCHEDULE' || basis === 'AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE')
    || !(window === null || window === 'AVAILABLE' || window === 'NO_REMAINING_WINDOW' || window === 'INVALID' || window === 'INCOMPLETE')
    || !(freshness === 'AVAILABLE' || freshness === 'STALE' || freshness === 'UNAVAILABLE' || freshness === 'UNKNOWN')) return undefined;
  return { severity, selectedWindowBasis: basis, selectedWindowState: window, estimateFreshness: freshness };
}

function parseClaim(value: unknown): MaritimeAiClaim | null {
  if (!record(value) || !nonempty(value.statement) || !Array.isArray(value.evidenceIds)
    || !value.evidenceIds.every(nonempty) || !nonempty(value.applicability)) return null;
  const relationshipType = value.relationshipType;
  if (!(relationshipType === undefined || relationshipType === null || isRelationship(relationshipType))) return null;
  const factReferences = value.factReferences;
  if (!(factReferences === undefined || stringList(factReferences))) return null;
  const riskReview = parseRiskReview(value.riskReview);
  if (riskReview === undefined) return null;
  const vesselRole = value.vesselRole;
  if (!(vesselRole === undefined || vesselRole === null || vesselRole === 'MOTHER' || vesselRole === 'FEEDER')) return null;
  if (!(value.observationType === undefined || nullableText(value.observationType))
    || !(value.associationScope === undefined || nullableText(value.associationScope))) return null;
  return {
    statement: value.statement,
    evidenceIds: [...value.evidenceIds],
    applicability: value.applicability,
    ...(vesselRole === undefined ? {} : { vesselRole }),
    ...(value.observationType === undefined ? {} : { observationType: value.observationType }),
    ...(value.associationScope === undefined ? {} : { associationScope: value.associationScope }),
    ...(factReferences === undefined ? {} : { factReferences: [...factReferences] }),
    ...(relationshipType === undefined ? {} : { relationshipType }),
    ...(riskReview === undefined ? {} : { riskReview }),
  };
}

function parseFact(value: unknown): MaritimeAiFact | null {
  if (!record(value) || !nonempty(value.factReference) || !nonempty(value.factType)
    || !nullableText(value.subject) || !nullableText(value.eventType) || !nullableText(value.timestamp)
    || !nullableText(value.previousValue) || !nullableText(value.currentValue) || !nullableText(value.evidenceId)
    || !nonempty(value.sourceType) || !nonempty(value.sourceMode) || !nonempty(value.associationScope)
    || !text(value.citation) || !(value.observedAt === undefined || nullableText(value.observedAt))
    || !(value.retrievedAtUtc === undefined || nullableText(value.retrievedAtUtc))) return null;
  return {
    factReference: value.factReference, factType: value.factType, subject: value.subject, eventType: value.eventType,
    timestamp: value.timestamp, previousValue: value.previousValue, currentValue: value.currentValue,
    evidenceId: value.evidenceId, sourceType: value.sourceType, sourceMode: value.sourceMode,
    associationScope: value.associationScope, citation: value.citation,
    ...(value.observedAt === undefined ? {} : { observedAt: value.observedAt }),
    ...(value.retrievedAtUtc === undefined ? {} : { retrievedAtUtc: value.retrievedAtUtc }),
  };
}

function isPresent<T>(value: T | null): value is T { return value !== null; }

function parseRiskSnapshot(value: unknown): MaritimeAiRiskSnapshot | null {
  if (!record(value) || typeof value.assessed !== 'boolean' || !stringList(value.calculationLimitations)
    || !stringList(value.canonicalFactReferences) || !nullableText(value.unavailableReason)
    || !nullableText(value.estimateUnavailableCode) || !nullableFinite(value.windowSeconds)
    || !nullableFinite(value.missedBySeconds)) return null;
  const severity = value.severity;
  const basis = value.selectedWindowBasis;
  const window = value.selectedWindowState;
  const freshness = value.estimateFreshness;
  if (!(severity === null || severity === 'LOW' || severity === 'MEDIUM' || severity === 'HIGH' || severity === 'CRITICAL')
    || !(basis === null || basis === 'PLANNED_SHIPMENT_SCHEDULE' || basis === 'AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE')
    || !(window === 'AVAILABLE' || window === 'MISSED' || window === 'INVALID' || window === 'INCOMPLETE')
    || !(freshness === 'AVAILABLE' || freshness === 'STALE' || freshness === 'UNAVAILABLE' || freshness === 'UNKNOWN')) return null;
  return {
    assessed: value.assessed, severity, selectedWindowBasis: basis, selectedWindowState: window,
    windowSeconds: value.windowSeconds, missedBySeconds: value.missedBySeconds, estimateFreshness: freshness,
    estimateUnavailableCode: value.estimateUnavailableCode, unavailableReason: value.unavailableReason,
    calculationLimitations: [...value.calculationLimitations], canonicalFactReferences: [...value.canonicalFactReferences],
  };
}

function parseBusinessContext(value: unknown): MaritimeAiBusinessContext | null {
  if (!record(value) || !(value.deadline === null || record(value.deadline))
    || !(value.comparison === null || record(value.comparison))) return null;
  let deadline: MaritimeAiBusinessDeadline | null = null;
  if (record(value.deadline)) {
    const item = value.deadline;
    if (!(item.state === 'RECORDED' || item.state === 'NOT_RECORDED' || item.state === 'UNKNOWN')
      || !nullableText(item.instant) || !nullableText(item.recordedTransshipmentPort)
      || !(item.applicableToCurrentPort === null || typeof item.applicableToCurrentPort === 'boolean')) return null;
    deadline = { state: item.state, instant: item.instant, recordedTransshipmentPort: item.recordedTransshipmentPort,
      applicableToCurrentPort: item.applicableToCurrentPort };
  }
  let comparison: MaritimeAiBusinessComparison | null = null;
  if (record(value.comparison)) {
    const item = value.comparison;
    if (!nullableText(item.estimatedArrivalAt) || !nullableText(item.comparedDeadline)
      || !nullableText(item.recordedTransshipmentPort)) return null;
    if (item.state === 'AVAILABLE' && (item.outcome === 'NO_PREDICTED_IMPACT' || item.outcome === 'EXCEEDS_DEADLINE')
      && item.unavailableReason === null) comparison = { state: 'AVAILABLE', outcome: item.outcome, unavailableReason: null,
        estimatedArrivalAt: item.estimatedArrivalAt, comparedDeadline: item.comparedDeadline, recordedTransshipmentPort: item.recordedTransshipmentPort };
    else if (item.state === 'UNAVAILABLE' && item.outcome === null
      && (item.unavailableReason === null || item.unavailableReason === 'INCOMPATIBLE_MILESTONE' || item.unavailableReason === 'MISSING_PREDICTION'
        || item.unavailableReason === 'MISSING_CONSTRAINT' || item.unavailableReason === 'STALE_PREDICTION')) comparison = {
        state: 'UNAVAILABLE', outcome: null, unavailableReason: item.unavailableReason,
        estimatedArrivalAt: item.estimatedArrivalAt, comparedDeadline: item.comparedDeadline, recordedTransshipmentPort: item.recordedTransshipmentPort,
      };
    else if (item.state === 'UNKNOWN' && item.outcome === null && item.unavailableReason === null) comparison = {
      state: 'UNKNOWN', outcome: null, unavailableReason: null, estimatedArrivalAt: item.estimatedArrivalAt,
      comparedDeadline: item.comparedDeadline, recordedTransshipmentPort: item.recordedTransshipmentPort,
    };
    else return null;
  }
  return { deadline, comparison };
}

function parseSummary(value: unknown): MaritimeAiDeterministicSummary | null {
  if (value === null) return null;
  if (!record(value) || !nonempty(value.summaryVersion) || !text(value.label)
    || !Array.isArray(value.shipmentScheduleFacts) || !Array.isArray(value.evidenceFacts)
    || !stringList(value.limitations)) return null;
  const schedule = value.shipmentScheduleFacts.map(parseFact);
  const evidence = value.evidenceFacts.map(parseFact);
  if (schedule.some(item => item === null) || evidence.some(item => item === null)) return null;
  let connectionRisk: MaritimeAiRiskSnapshot | null | undefined;
  if (value.connectionRisk !== undefined) {
    connectionRisk = value.connectionRisk === null ? null : parseRiskSnapshot(value.connectionRisk) ?? undefined;
    if (connectionRisk === undefined) return null;
  }
  let businessContext: MaritimeAiDeterministicSummary['businessContext'];
  if (value.businessContext !== undefined) {
    if (value.businessContext === null) businessContext = null;
    else if (!record(value.businessContext) || !Array.isArray(value.businessContext.canonicalFactReferences)
      || !stringList(value.businessContext.canonicalFactReferences)) return null;
    else {
      const context = value.businessContext.context === null ? null : parseBusinessContext(value.businessContext.context);
      if (value.businessContext.context !== null && context === null) return null;
      businessContext = { context, canonicalFactReferences: [...value.businessContext.canonicalFactReferences] };
    }
  }
  return { summaryVersion: value.summaryVersion, label: value.label, shipmentScheduleFacts: schedule.filter(isPresent),
    evidenceFacts: evidence.filter(isPresent), limitations: [...value.limitations],
    ...(connectionRisk === undefined ? {} : { connectionRisk }),
    ...(businessContext === undefined ? {} : { businessContext }) };
}

function parseAssessment(value: unknown): MaritimeAiAssessment | null {
  if (!record(value) || value.assessmentId !== null
    || !(value.state === 'GENERATED' || value.state === 'ABSTAINED' || value.state === 'FAILED')
    || !nonempty(value.validationStatus) || !nonempty(value.bundleVersion) || !nonempty(value.bundleHash)
    || !nonempty(value.assessmentCutoffUtc) || !nonempty(value.provider) || !text(value.model)
    || !nonempty(value.promptVersion) || !(value.generatedAtUtc === null || text(value.generatedAtUtc))
    || !(value.limitation === null || text(value.limitation))) return null;
  let interpretation: MaritimeAiAssessment['interpretation'] = null;
  if (value.interpretation !== null) {
    const item = value.interpretation;
    if (!record(item) || !text(item.summary) || !Array.isArray(item.claims)
      || !stringList(item.uncertainties) || !stringList(item.followUpConsiderations)
      || typeof item.evidenceSufficient !== 'boolean'
      || !(item.formalRecommendationCode === null || text(item.formalRecommendationCode))) return null;
    const claims = item.claims.map(parseClaim);
    if (claims.some(claim => claim === null)) return null;
    interpretation = { summary: item.summary, claims: claims.filter(isPresent), uncertainties: [...item.uncertainties],
      followUpConsiderations: [...item.followUpConsiderations], evidenceSufficient: item.evidenceSufficient,
      formalRecommendationCode: item.formalRecommendationCode };
  }
  const summary = parseSummary(value.deterministicSummary);
  if (summary === null && value.deterministicSummary !== null) return null;
  return { assessmentId: null, state: value.state, validationStatus: value.validationStatus,
    bundleVersion: value.bundleVersion, bundleHash: value.bundleHash, assessmentCutoffUtc: value.assessmentCutoffUtc,
    provider: value.provider, model: value.model, promptVersion: value.promptVersion,
    generatedAtUtc: value.generatedAtUtc, interpretation, limitation: value.limitation, deterministicSummary: summary };
}

export function decodeScenarioInterpretationResponse(value: unknown,
  sessionId: string, shipmentId: number, request: ScenarioInterpretationRequest): ScenarioInterpretationResponse | null {
  if (!record(value) || value.sessionId !== sessionId || value.epochId !== request.expectedEpochId
    || value.snapshotId !== request.expectedSnapshotId || value.revision !== request.expectedRevision
    || value.simulationCutoffUtc !== request.expectedSimulationCutoffUtc || value.shipmentId !== shipmentId
    || value.scenarioShipmentKey !== request.scenarioShipmentKey) return null;
  if (!uuid(value.sessionId) || !uuid(value.epochId) || !uuid(value.snapshotId)
    || typeof value.revision !== 'number' || !Number.isSafeInteger(value.revision) || !text(value.simulationCutoffUtc)
    || (value.scenarioShipmentKey !== 'A' && value.scenarioShipmentKey !== 'B')
    || typeof value.shipmentId !== 'number' || !Number.isSafeInteger(value.shipmentId) || value.shipmentId <= 0) return null;
  const assessment = parseAssessment(value.assessment);
  if (!assessment || assessment.assessmentCutoffUtc !== request.expectedSimulationCutoffUtc) return null;
  return { sessionId: value.sessionId, epochId: value.epochId, snapshotId: value.snapshotId,
    revision: value.revision, simulationCutoffUtc: value.simulationCutoffUtc,
    scenarioShipmentKey: value.scenarioShipmentKey, shipmentId: value.shipmentId, assessment };
}

export async function generateScenarioInterpretation(token: string, sessionId: string, shipmentId: number,
  request: ScenarioInterpretationRequest, signal?: AbortSignal): Promise<ScenarioInterpretationResponse> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(new DOMException('Timed out', 'TimeoutError')), 15_000);
  const abort = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', abort, { once: true });
  try {
    const response = await fetch(`/api/scenarios/v1/sessions/${encodeURIComponent(sessionId)}/shipments/${shipmentId}/interpretation`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(request), signal: controller.signal, cache: 'no-store',
    });
    let data: unknown;
    try { data = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null; }
    catch { data = null; }
    if (!response.ok) {
      const apiError = record(data) && nonempty(data.code) && nonempty(data.message)
        ? { code: data.code, message: data.message, retryable: data.retryable === true } : null;
      throw new ScenarioAiApiError(response.status === 401 ? 'Your session has ended. Log in again.'
        : apiError?.message ?? 'Scenario interpretation could not be completed.', response.status,
      apiError?.code ?? null, apiError?.retryable ?? false);
    }
    const decoded = decodeScenarioInterpretationResponse(data, sessionId, shipmentId, request);
    if (!decoded) throw new ScenarioAiApiError('DRIFT returned a malformed or mismatched scenario interpretation.', 502, 'INVALID_SCENARIO_AI_RESPONSE');
    return decoded;
  } catch (error) {
    if (error instanceof ScenarioAiApiError) throw error;
    if (controller.signal.aborted) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      throw new ScenarioAiApiError('Scenario interpretation timed out. No automatic retry was made.', 0, 'REQUEST_TIMEOUT');
    }
    if (error instanceof TypeError) throw new ScenarioAiApiError('We could not reach DRIFT. No automatic retry was made.', 0, 'NETWORK_ERROR');
    throw error;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', abort);
  }
}
