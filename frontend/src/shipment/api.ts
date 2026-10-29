import { ApiError } from '../signup/api';
import { RECOMMENDATION_RESPONSES, type RecommendationCode } from './recommendationCatalog';
import { shipmentFilterSearchParams, type ShipmentFilters } from './shipmentFilters';
import { type ShipmentFields } from './validation';

export { IMPACT_NOT_RETURNED, RECOMMENDATION_CODES, RECOMMENDATION_RESPONSES, type RecommendationCode } from './recommendationCatalog';

export type ConnectionWindow = {
  duration: string;
  totalSeconds: number;
};

export type RemainingConnectionWindow = {
  status: 'AVAILABLE';
  duration: string;
  totalSeconds: number;
  missedBy: null;
  missedBySeconds: null;
} | {
  status: 'MISSED';
  duration: null;
  totalSeconds: null;
  missedBy: string;
  missedBySeconds: number;
};

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type ShipmentRisk = {
  level: RiskLevel;
  explanation: string;
};

export type RiskContributionCode =
  | 'WINDOW_IN_USE'
  | 'REMAINING_DURATION'
  | 'MISSED_BY'
  | 'PLANNED_DURATION'
  | 'PLANNED_WINDOW'
  | 'ESTIMATE_NOT_USED';

export type RiskContribution = {
  code: RiskContributionCode;
  label: string;
  value: string;
};

export type RiskContributions = {
  assessed: true;
  notAssessed: null;
  contributions: RiskContribution[];
} | {
  assessed: false;
  notAssessed: string;
  contributions: [];
};

export type RecommendationImpact = {
  outcome: 'AFFECTS_CONSTRAINTS' | 'NO_PREDICTED_IMPACT';
  detail: null;
} | {
  outcome: 'UNAVAILABLE';
  detail: string;
};

export type RecommendedResponse = {
  code: RecommendationCode;
  label: string;
  wording: string;
};

export type ShipmentRecommendation = {
  available: true;
  unavailable: null;
  response: RecommendedResponse;
  riskLevel: RiskLevel;
  riskExplanation: string;
  impact: Extract<RecommendationImpact, { outcome: 'AFFECTS_CONSTRAINTS' | 'NO_PREDICTED_IMPACT' }>;
} | {
  available: false;
  unavailable: string;
  response: null;
  riskLevel: RiskLevel | null;
  riskExplanation: string | null;
  impact: RecommendationImpact;
};

export type RecommendationDecisionOutcome = 'APPROVED' | 'MODIFIED' | 'DISMISSED';

export type RecommendationDecision = {
  outcome: RecommendationDecisionOutcome;
  recommendationName: string;
  recommendationWording: string;
  selectedResponseName: string | null;
  selectedResponseWording: string | null;
  decidedByUserId: number;
  decidedByName: string;
  decidedAt: string;
};

export type RecommendationDecisionRequest =
  | { outcome: 'APPROVED' | 'DISMISSED' }
  | { outcome: 'MODIFIED'; selectedResponse: RecommendationCode };

export type ShipmentStatus = 'ACTIVE' | 'ARCHIVED';

export type VesselPosition = {
  mmsi: string;
  vesselName: string | null;
  latitude: number;
  longitude: number;
  speedOverGroundKnots: number | null;
  courseOverGroundDegrees: number | null;
  trueHeadingDegrees: number | null;
  ingestedAt: string;
  source?: 'AIS_STREAM' | 'SEEDED';
};

export type OperationalState = {
  label: string;
  description: string;
  unreliable: boolean;
  ingestedAt: string | null;
};

export type AisFeedStatusName = 'LIVE' | 'DELAYED' | 'OFFLINE';

export type AisFeedStatus = {
  status: AisFeedStatusName;
  lastReceivedAt: string | null;
};

export type ImporterOrganisation = {
  id: number;
  code: string;
  name: string;
};

export type OrganisationPage = {
  items: ImporterOrganisation[];
  page: number;
  size: number;
  total: number;
};

export type ArrivalClassification = 'AHEAD_OF_PLAN' | 'ON_SCHEDULE' | 'BEHIND_PLAN';

export type ArrivalComparison = {
  estimatedArrivalAt: string;
  limitation: string;
  speedOverGroundKnots: number;
  classification: ArrivalClassification;
  difference: string;
  differenceSeconds: number;
  differenceNanos: number;
  lastReceivedAt: string;
  possiblyOutdated: boolean;
  unavailableReason: null;
} | {
  estimatedArrivalAt: null;
  limitation: null;
  speedOverGroundKnots: null;
  classification: null;
  difference: null;
  differenceSeconds: null;
  differenceNanos: null;
  lastReceivedAt: null;
  possiblyOutdated: null;
  unavailableReason: { code: string; message: string };
};

export type Shipment = {
  id: number;
  shipmentReference: string;
  origin: string;
  destination: string;
  transshipmentPort: string;
  motherVessel: string;
  plannedMotherArrivalAt: string;
  feederVessel: string | null;
  plannedFeederDepartureAt: string | null;
  createdAt: string;
  version: number;
  companyId?: number | null;
  connectionWindow: ConnectionWindow | null;
  remainingConnectionWindow: RemainingConnectionWindow | null;
  risk: ShipmentRisk | null;
  riskContributions?: RiskContributions | null;
  status: ShipmentStatus | null;
  motherVesselPosition: VesselPosition | null;
  feederVesselPosition: VesselPosition | null;
  importer?: ImporterOrganisation | null;
  motherVesselImo?: string | null;
  motherVesselMmsi?: string | null;
  motherVesselOperator?: string | null;
  feederVesselImo?: string | null;
  feederVesselMmsi?: string | null;
  feederVesselOperator?: string | null;
  motherVesselNameWarning?: string | null;
  feederVesselNameWarning?: string | null;
  motherOperationalState?: OperationalState | null;
  feederOperationalState?: OperationalState | null;
  aisFeed?: AisFeedStatus | null;
  arrivalComparison: ArrivalComparison | null;
  updatedAt?: string | null;
  updatedByName?: string | null;
  recommendation?: ShipmentRecommendation | null;
  recommendationDecision?: RecommendationDecision | null;
};

export type Cdg201ArrivalEvidence = {
  contractVersion: string;
  evidenceState: 'RECORDED_EVIDENCE_REPLAY';
  vesselName: string;
  vesselImo: string;
  previousExpectedArrival: string;
  revisedExpectedArrival: string;
  previousExpectedArrivalSgt: string;
  previousExpectedArrivalUtc: string;
  revisedExpectedArrivalSgt: string;
  revisedExpectedArrivalUtc: string;
  revisionMinutes: number;
  previousCollectionUtc: string;
  latestCollectionUtc: string;
  previousCollectionRunId: string;
  latestCollectionRunId: string;
  previousSourceManifest: string;
  previousSourceRawFile: string;
  sourceManifest: string;
  sourceRawFile: string;
  confidence: string;
  ambiguity: string;
  sourceTimestampSemantics: string;
  locationFrom: string;
  locationTo: string;
  locationMappingStatus: string;
  locationReferences: Cdg201MpaLocationReference[];
  shipmentAssociation: string;
  limitations: string;
  simulatedScenario: {
    state: 'SIMULATED_CONNECTION_ASSUMPTIONS';
    originalConnectionMarginMinutes: number;
    revisedConnectionMarginMinutes: number;
    reductionMinutes: number;
    simulatedRiskBefore: string;
    simulatedRiskAfter: string;
    disclaimer: string;
  };
};

export type Cdg201MpaLocationReference = {
  rawLocationCode: string;
  sourceApi: string;
  sourceField: string;
  officialDescription: string;
  derivedCategory: string;
  mappingStatus: string;
  referenceProvenance: string;
  limitation: string;
};

const UNEXPECTED = 'DRIFT returned an unexpected response. Please try again shortly.';

export async function listShipments(token: string, filters?: ShipmentFilters): Promise<Shipment[]> {
  const query = filters ? shipmentFilterSearchParams(filters).toString() : '';
  const data = await send(query ? `/api/shipments?${query}` : '/api/shipments', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  const shipments = data.map(asShipment);
  if (shipments.some(shipment => shipment === null)) throw new ApiError(UNEXPECTED, 502);
  return shipments as Shipment[];
}

export async function getShipment(token: string, shipmentId: string): Promise<Shipment> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}`, token);
  const shipment = asShipment(data);
  if (!shipment) throw new ApiError(UNEXPECTED, 502);
  return shipment;
}

export async function recordRecommendationDecision(
  token: string,
  shipmentId: number,
  request: RecommendationDecisionRequest,
): Promise<RecommendationDecision> {
  const body = request.outcome === 'MODIFIED'
    ? { outcome: request.outcome, selectedResponse: request.selectedResponse }
    : { outcome: request.outcome };
  const data = await send(`/api/shipments/${encodeURIComponent(String(shipmentId))}/recommendation-decision`, token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const decision = parseRecommendationDecision(data);
  if (!decision) throw new ApiError(UNEXPECTED, 502);
  return decision;
}

/** Read-only CDG-201 fixture/replay evidence. Null means no replay matches this shipment. */
export async function getCdg201ArrivalEvidence(token: string, shipmentId: string): Promise<Cdg201ArrivalEvidence | null> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/cdg201-arrival-evidence`, token);
  if (!isRecord(data) || data.readOnlyReplay !== true) throw new ApiError(UNEXPECTED, 502);
  if (data.evidence === null) return null;
  const evidence = asCdg201ArrivalEvidence(data.evidence);
  if (!evidence) throw new ApiError(UNEXPECTED, 502);
  return evidence;
}

export type ShipmentTracking = {
  motherVesselName: string;
  motherVessel: VesselPosition | null;
  motherVesselNameWarning: string | null;
  feederVesselName: string | null;
  feederVessel: VesselPosition | null;
  feederVesselNameWarning: string | null;
};

export async function archiveShipment(token: string, shipmentId: number): Promise<string> {
  return changeShipmentStatus(token, shipmentId, 'ARCHIVED');
}

export async function restoreShipment(token: string, shipmentId: number): Promise<string> {
  return changeShipmentStatus(token, shipmentId, 'ACTIVE');
}

async function changeShipmentStatus(token: string, shipmentId: number, status: ShipmentStatus): Promise<string> {
  const data = await send(`/api/shipments/${encodeURIComponent(String(shipmentId))}/status`, token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  });
  if (!isRecord(data) || typeof data.message !== 'string' || data.message.trim() === '') {
    throw new ApiError(UNEXPECTED, 502);
  }
  return data.message;
}

export async function getShipmentTracking(token: string, shipmentId: string): Promise<ShipmentTracking> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/tracking`, token);
  const tracking = asTracking(data);
  if (!tracking) throw new ApiError(UNEXPECTED, 502);
  return tracking;
}

export type TrackVessel = 'MOTHER' | 'FEEDER';
export type TrackWindow = 6 | 24 | 72;

export type TrackPoint = {
  id: number;
  latitude: number;
  longitude: number;
  speedOverGroundKnots: number | null;
  courseOverGroundDegrees: number | null;
  trueHeadingDegrees: number | null;
  ingestedAt: string;
};

export type VesselTrack = {
  vessel: TrackVessel;
  vesselName: string | null;
  mmsi: string | null;
  windowHours: number;
  from: string;
  to: string;
  slowdownThresholdKnots: number;
  pointLimit: number;
  truncated: boolean;
  points: TrackPoint[];
};

export async function getVesselTrack(token: string, shipmentId: string, vessel: TrackVessel, hours: TrackWindow): Promise<VesselTrack> {
  const query = new URLSearchParams({ vessel, hours: String(hours) }).toString();
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/track-history?${query}`, token);
  const track = asTrack(data);
  if (!track) throw new ApiError(UNEXPECTED, 502);
  return track;
}

export async function createShipment(token: string, shipment: ShipmentFields): Promise<Shipment> {
  const data = await send('/api/shipments', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(shipment),
  });
  const created = asShipment(data);
  if (!created) throw new ApiError(UNEXPECTED, 502);
  return created;
}

export type ShipmentUpdate = ShipmentFields & { version: number };

export async function updateShipment(token: string, shipmentId: number, shipment: ShipmentUpdate): Promise<Shipment> {
  const data = await send(`/api/shipments/${encodeURIComponent(String(shipmentId))}`, token, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(shipment),
  });
  const updated = asShipment(data);
  if (!updated) throw new ApiError(UNEXPECTED, 502);
  return updated;
}

export async function searchImporterOrganisations(token: string, name: string): Promise<OrganisationPage> {
  const query = new URLSearchParams({ type: 'importer', name, page: '0', size: '20' });
  const data = await send(`/api/organisations?${query}`, token);
  const page = asOrganisationPage(data);
  if (!page) throw new ApiError(UNEXPECTED, 502);
  return page;
}

export async function linkShipmentImporter(token: string, shipmentId: number, importerCompanyId: number | null): Promise<Shipment> {
  const data = await send(`/api/shipments/${encodeURIComponent(String(shipmentId))}/link-importer`, token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ importerCompanyId }),
  });
  const linked = asShipment(data);
  if (!linked) throw new ApiError(UNEXPECTED, 502);
  return linked;
}

export type ShipmentImportJobStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export type ShipmentImportJob = {
  id: number;
  status: ShipmentImportJobStatus;
  originalFilename: string;
  fileSizeBytes: number;
  totalRows: number;
  processedRows: number;
  importedCount: number;
  failedCount: number;
  failureMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
};

export type ShipmentImportError = {
  rowNumber: number;
  column: string | null;
  code: string;
  message: string;
};

export async function submitShipmentImport(token: string, file: File): Promise<ShipmentImportJob> {
  const form = new FormData();
  form.append('file', file);
  const data = await send('/api/shipments/import', token, { method: 'POST', body: form });
  const job = asShipmentImportJob(data);
  if (!job) throw new ApiError(UNEXPECTED, 502);
  return job;
}

export async function getShipmentImportJob(token: string, jobId: string): Promise<ShipmentImportJob> {
  const data = await send(`/api/shipments/import/${encodeURIComponent(jobId)}`, token);
  const job = asShipmentImportJob(data);
  if (!job) throw new ApiError(UNEXPECTED, 502);
  return job;
}

export async function getShipmentImportErrors(token: string, jobId: string): Promise<ShipmentImportError[]> {
  const data = await send(`/api/shipments/import/${encodeURIComponent(jobId)}/errors`, token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  const errors = data.map(asShipmentImportError);
  if (errors.some(error => error === null)) throw new ApiError(UNEXPECTED, 502);
  return errors as ShipmentImportError[];
}

async function send(path: string, token: string, init: RequestInit = {}): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { ...init.headers, Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(15000),
      cache: 'no-store',
    });
  } catch (error) {
    if (error instanceof TypeError || (error instanceof DOMException && ['TimeoutError', 'AbortError'].includes(error.name))) {
      throw new ApiError('We could not reach DRIFT. Check your connection and try again.', 0);
    }
    throw error;
  }
  if (response.status === 401) throw new ApiError('Your session has ended. Log in again.', 401);
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new ApiError(UNEXPECTED, response.status);
  }
  const data: unknown = await response.json();
  if (!response.ok) {
    const fields: Record<string, string> = {};
    if (isRecord(data) && isRecord(data.errors)) {
      for (const [key, value] of Object.entries(data.errors)) if (typeof value === 'string') fields[key] = value;
    }
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status, fields);
  }
  return data;
}

function isNullableText(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asCdg201ArrivalEvidence(value: unknown): Cdg201ArrivalEvidence | null {
  if (!isRecord(value)) return null;
  const text = ['contractVersion', 'evidenceState', 'vesselName', 'vesselImo', 'previousExpectedArrival', 'revisedExpectedArrival', 'previousExpectedArrivalSgt', 'previousExpectedArrivalUtc', 'revisedExpectedArrivalSgt', 'revisedExpectedArrivalUtc',
    'previousCollectionUtc', 'latestCollectionUtc', 'previousCollectionRunId', 'latestCollectionRunId', 'previousSourceManifest', 'previousSourceRawFile', 'sourceManifest', 'sourceRawFile',
    'confidence', 'ambiguity', 'sourceTimestampSemantics', 'locationFrom', 'locationTo', 'locationMappingStatus', 'shipmentAssociation', 'limitations'] as const;
  if (!text.every(key => typeof value[key] === 'string') || value.evidenceState !== 'RECORDED_EVIDENCE_REPLAY'
    || !isFiniteNumber(value.revisionMinutes) || !isRecord(value.simulatedScenario)) return null;
  if (!Array.isArray(value.locationReferences)) return null;
  const locationReferences = value.locationReferences.map(asCdg201MpaLocationReference);
  if (locationReferences.some(reference => reference === null)) return null;
  const scenario = value.simulatedScenario;
  const scenarioText = ['state', 'simulatedRiskBefore', 'simulatedRiskAfter', 'disclaimer'] as const;
  const scenarioNumbers = ['originalConnectionMarginMinutes', 'revisedConnectionMarginMinutes', 'reductionMinutes'] as const;
  if (!scenarioText.every(key => typeof scenario[key] === 'string') || !scenarioNumbers.every(key => isFiniteNumber(scenario[key]))
    || scenario.state !== 'SIMULATED_CONNECTION_ASSUMPTIONS') return null;
  return {
    contractVersion: value.contractVersion as string, evidenceState: 'RECORDED_EVIDENCE_REPLAY', vesselName: value.vesselName as string,
    vesselImo: value.vesselImo as string, previousExpectedArrival: value.previousExpectedArrival as string,
    revisedExpectedArrival: value.revisedExpectedArrival as string, previousExpectedArrivalSgt: value.previousExpectedArrivalSgt as string,
    previousExpectedArrivalUtc: value.previousExpectedArrivalUtc as string, revisedExpectedArrivalSgt: value.revisedExpectedArrivalSgt as string,
    revisedExpectedArrivalUtc: value.revisedExpectedArrivalUtc as string, revisionMinutes: value.revisionMinutes as number,
    previousCollectionUtc: value.previousCollectionUtc as string, latestCollectionUtc: value.latestCollectionUtc as string,
    previousCollectionRunId: value.previousCollectionRunId as string, latestCollectionRunId: value.latestCollectionRunId as string,
    previousSourceManifest: value.previousSourceManifest as string, previousSourceRawFile: value.previousSourceRawFile as string,
    sourceManifest: value.sourceManifest as string, sourceRawFile: value.sourceRawFile as string,
    confidence: value.confidence as string, ambiguity: value.ambiguity as string,
    sourceTimestampSemantics: value.sourceTimestampSemantics as string, locationFrom: value.locationFrom as string,
    locationTo: value.locationTo as string, locationMappingStatus: value.locationMappingStatus as string,
    locationReferences: locationReferences as Cdg201MpaLocationReference[],
    shipmentAssociation: value.shipmentAssociation as string, limitations: value.limitations as string,
    simulatedScenario: {
      state: 'SIMULATED_CONNECTION_ASSUMPTIONS', originalConnectionMarginMinutes: scenario.originalConnectionMarginMinutes as number,
      revisedConnectionMarginMinutes: scenario.revisedConnectionMarginMinutes as number, reductionMinutes: scenario.reductionMinutes as number,
      simulatedRiskBefore: scenario.simulatedRiskBefore as string, simulatedRiskAfter: scenario.simulatedRiskAfter as string,
      disclaimer: scenario.disclaimer as string,
    },
  };
}

function asCdg201MpaLocationReference(value: unknown): Cdg201MpaLocationReference | null {
  if (!isRecord(value)) return null;
  const text = ['rawLocationCode', 'sourceApi', 'sourceField', 'officialDescription', 'derivedCategory',
    'mappingStatus', 'referenceProvenance', 'limitation'] as const;
  if (!text.every(key => typeof value[key] === 'string')) return null;
  return {
    rawLocationCode: value.rawLocationCode as string,
    sourceApi: value.sourceApi as string,
    sourceField: value.sourceField as string,
    officialDescription: value.officialDescription as string,
    derivedCategory: value.derivedCategory as string,
    mappingStatus: value.mappingStatus as string,
    referenceProvenance: value.referenceProvenance as string,
    limitation: value.limitation as string,
  };
}

function isConnectionWindow(value: unknown): value is ConnectionWindow {
  return isRecord(value)
    && typeof value.duration === 'string'
    && value.duration.trim().length > 0
    && typeof value.totalSeconds === 'number'
    && Number.isFinite(value.totalSeconds);
}

const RISK_LEVELS: readonly unknown[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

function remainingConnectionWindowOf(value: Record<string, unknown>): RemainingConnectionWindow | null | undefined {
  if (!('remainingConnectionWindow' in value) || value.remainingConnectionWindow == null) return null;
  const window = value.remainingConnectionWindow;
  if (!isRecord(window) || (window.status !== 'AVAILABLE' && window.status !== 'MISSED')) return undefined;
  if (window.status === 'AVAILABLE') {
    if (typeof window.duration !== 'string' || window.duration.trim().length === 0) return undefined;
    if (typeof window.totalSeconds !== 'number' || !Number.isFinite(window.totalSeconds) || window.totalSeconds < 0) return undefined;
    if (window.missedBy != null || window.missedBySeconds != null) return undefined;
    return {
      status: 'AVAILABLE',
      duration: window.duration,
      totalSeconds: window.totalSeconds,
      missedBy: null,
      missedBySeconds: null,
    };
  }
  if (window.duration != null || window.totalSeconds != null) return undefined;
  if (typeof window.missedBy !== 'string' || window.missedBy.trim().length === 0) return undefined;
  if (typeof window.missedBySeconds !== 'number' || !Number.isFinite(window.missedBySeconds) || window.missedBySeconds < 0) return undefined;
  return {
    status: 'MISSED',
    duration: null,
    totalSeconds: null,
    missedBy: window.missedBy,
    missedBySeconds: window.missedBySeconds,
  };
}

function isRiskLevel(value: unknown): value is RiskLevel {
  return RISK_LEVELS.includes(value);
}

function isRisk(value: unknown): value is ShipmentRisk {
  return isRecord(value)
    && isRiskLevel(value.level)
    && typeof value.explanation === 'string'
    && value.explanation.trim().length > 0;
}

const RECOMMENDATION_FOR: Record<RiskLevel, Record<'AFFECTS_CONSTRAINTS' | 'NO_PREDICTED_IMPACT', RecommendationCode>> = {
  LOW: { AFFECTS_CONSTRAINTS: 'MONITOR_CONSTRAINTS', NO_PREDICTED_IMPACT: 'NO_ACTION' },
  MEDIUM: { AFFECTS_CONSTRAINTS: 'REVIEW_PLAN', NO_PREDICTED_IMPACT: 'NOTE_TIMING' },
  HIGH: { AFFECTS_CONSTRAINTS: 'PREPARE_INTERVENTION', NO_PREDICTED_IMPACT: 'WATCH_CONNECTION' },
  CRITICAL: { AFFECTS_CONSTRAINTS: 'ACT_ON_CONNECTION', NO_PREDICTED_IMPACT: 'CONFIRM_CONNECTION' },
};

function recommendationOf(value: Record<string, unknown>, risk: ShipmentRisk | null): ShipmentRecommendation | null | undefined {
  if (!('recommendation' in value) || value.recommendation == null) return null;
  const recommendation = parseRecommendation(value.recommendation);
  if (!recommendation) return undefined;
  if (risk == null) {
    if (recommendation.riskLevel != null || recommendation.riskExplanation != null) return undefined;
  } else if (recommendation.riskLevel !== risk.level || recommendation.riskExplanation !== risk.explanation) {
    return undefined;
  }
  return recommendation;
}

function parseRecommendation(value: unknown): ShipmentRecommendation | undefined {
  if (!isRecord(value) || typeof value.available !== 'boolean') return undefined;
  const impact = parseRecommendationImpact(value.impact);
  if (!impact) return undefined;
  const riskLevel = value.riskLevel == null ? null : (isRiskLevel(value.riskLevel) ? value.riskLevel : undefined);
  if (riskLevel === undefined) return undefined;
  if (value.riskExplanation != null && (typeof value.riskExplanation !== 'string' || value.riskExplanation.trim().length === 0)) {
    return undefined;
  }
  const riskExplanation = typeof value.riskExplanation === 'string' ? value.riskExplanation : null;
  if (riskLevel == null && riskExplanation != null) return undefined;
  if (riskLevel != null && riskExplanation == null) return undefined;
  if (value.available) {
    if (value.unavailable != null || riskLevel == null || riskExplanation == null || impact.outcome === 'UNAVAILABLE') return undefined;
    const response = parseRecommendedResponse(value.response);
    if (!response || response.code !== RECOMMENDATION_FOR[riskLevel][impact.outcome]) return undefined;
    return { available: true, unavailable: null, response, riskLevel, riskExplanation, impact };
  }
  if (typeof value.unavailable !== 'string' || value.unavailable.trim().length === 0 || value.response != null) return undefined;
  if (riskLevel != null && impact.outcome !== 'UNAVAILABLE') return undefined;
  return { available: false, unavailable: value.unavailable, response: null, riskLevel, riskExplanation, impact };
}

function parseRecommendationImpact(value: unknown): RecommendationImpact | undefined {
  if (!isRecord(value)) return undefined;
  if (value.outcome === 'UNAVAILABLE') {
    if (typeof value.detail !== 'string' || value.detail.trim().length === 0) return undefined;
    return { outcome: 'UNAVAILABLE', detail: value.detail };
  }
  if (value.outcome !== 'AFFECTS_CONSTRAINTS' && value.outcome !== 'NO_PREDICTED_IMPACT') return undefined;
  if (value.detail != null) return undefined;
  return { outcome: value.outcome, detail: null };
}

function parseRecommendedResponse(value: unknown): RecommendedResponse | undefined {
  if (!isRecord(value) || typeof value.code !== 'string' || !(value.code in RECOMMENDATION_RESPONSES)) return undefined;
  const code = value.code as RecommendationCode;
  const expected = RECOMMENDATION_RESPONSES[code];
  if (value.label !== expected.label || value.wording !== expected.wording) return undefined;
  return { code, label: expected.label, wording: expected.wording };
}

function recommendationDecisionOf(value: Record<string, unknown>): RecommendationDecision | null | undefined {
  if (!('recommendationDecision' in value) || value.recommendationDecision == null) return null;
  return parseRecommendationDecision(value.recommendationDecision);
}

function parseRecommendationDecision(value: unknown): RecommendationDecision | undefined {
  if (!isRecord(value)) return undefined;
  const outcome = value.outcome;
  if (outcome !== 'APPROVED' && outcome !== 'MODIFIED' && outcome !== 'DISMISSED') return undefined;
  if (!isPresentText(value.recommendationName) || !isPresentText(value.recommendationWording)) return undefined;
  if (!isFiniteNumber(value.decidedByUserId) || value.decidedByUserId <= 0) return undefined;
  if (!isPresentText(value.decidedByName) || !isPresentText(value.decidedAt)) return undefined;
  const selectedName = value.selectedResponseName;
  const selectedWording = value.selectedResponseWording;
  if (outcome === 'MODIFIED') {
    if (!isPresentText(selectedName) || !isPresentText(selectedWording)) return undefined;
    if (selectedName.trim() === value.recommendationName.trim()) return undefined;
    return {
      outcome,
      recommendationName: value.recommendationName,
      recommendationWording: value.recommendationWording,
      selectedResponseName: selectedName,
      selectedResponseWording: selectedWording,
      decidedByUserId: value.decidedByUserId,
      decidedByName: value.decidedByName,
      decidedAt: value.decidedAt,
    };
  }
  if (selectedName != null || selectedWording != null) return undefined;
  return {
    outcome,
    recommendationName: value.recommendationName,
    recommendationWording: value.recommendationWording,
    selectedResponseName: null,
    selectedResponseWording: null,
    decidedByUserId: value.decidedByUserId,
    decidedByName: value.decidedByName,
    decidedAt: value.decidedAt,
  };
}

const CONTRIBUTION_LABELS: Record<RiskContributionCode, string> = {
  WINDOW_IN_USE: 'Window in use',
  REMAINING_DURATION: 'Remaining duration',
  MISSED_BY: 'Missed by',
  PLANNED_DURATION: 'Planned duration',
  PLANNED_WINDOW: 'Planned window',
  ESTIMATE_NOT_USED: 'Estimate not used',
};

const MEASURED_CONTRIBUTIONS: readonly RiskContributionCode[] = [
  'REMAINING_DURATION', 'MISSED_BY', 'PLANNED_DURATION', 'PLANNED_WINDOW',
];

function riskContributionsOf(value: Record<string, unknown>): RiskContributions | null | undefined {
  if (!('riskContributions' in value) || value.riskContributions == null) return null;
  return parseRiskContributions(value.riskContributions);
}

function parseRiskContributions(value: unknown): RiskContributions | undefined {
  if (!isRecord(value) || typeof value.assessed !== 'boolean' || !Array.isArray(value.contributions)) return undefined;
  if (value.assessed) {
    if (value.notAssessed != null) return undefined;
    const contributions = value.contributions.map(parseContribution);
    if (contributions.some(contribution => contribution === undefined)) return undefined;
    const parsed = contributions as RiskContribution[];
    if (!contributionSet(parsed)) return undefined;
    return { assessed: true, notAssessed: null, contributions: parsed };
  }
  if (typeof value.notAssessed !== 'string' || value.notAssessed.trim().length === 0 || value.contributions.length > 0) {
    return undefined;
  }
  return { assessed: false, notAssessed: value.notAssessed, contributions: [] };
}

function parseContribution(value: unknown): RiskContribution | undefined {
  if (!isRecord(value) || typeof value.code !== 'string' || !(value.code in CONTRIBUTION_LABELS)) return undefined;
  const code = value.code as RiskContributionCode;
  if (value.label !== CONTRIBUTION_LABELS[code] || typeof value.value !== 'string' || value.value.trim().length === 0) {
    return undefined;
  }
  if (code === 'WINDOW_IN_USE' && value.value !== 'Remaining connection window' && value.value !== 'Planned connection window') {
    return undefined;
  }
  if (code === 'PLANNED_WINDOW' && value.value !== 'The planned connection window is not positive.') return undefined;
  return { code, label: value.label, value: value.value };
}

function contributionSet(contributions: RiskContribution[]): boolean {
  const codes = contributions.map(contribution => contribution.code);
  if (new Set(codes).size !== codes.length || !codes.includes('WINDOW_IN_USE') || contributions.length === 0) return false;
  const measured = codes.filter(code => MEASURED_CONTRIBUTIONS.includes(code));
  if (measured.length !== 1) return false;
  const planned = measured[0] === 'PLANNED_DURATION' || measured[0] === 'PLANNED_WINDOW';
  return codes.includes('ESTIMATE_NOT_USED') === planned;
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isOptionalFiniteNumber(value: unknown): value is number | null {
  return value == null || isFiniteNumber(value);
}

const AIS_FEED_STATUS: readonly AisFeedStatusName[] = ['LIVE', 'DELAYED', 'OFFLINE'];

function aisFeedOf(value: Record<string, unknown>): AisFeedStatus | null | undefined {
  if (!('aisFeed' in value) || value.aisFeed == null) return null;
  const feed = value.aisFeed;
  if (!isRecord(feed) || !AIS_FEED_STATUS.some(status => status === feed.status)) return undefined;
  const status = feed.status as AisFeedStatusName;
  if (status === 'OFFLINE') {
    if (feed.lastReceivedAt != null && typeof feed.lastReceivedAt !== 'string') return undefined;
    return { status: 'OFFLINE', lastReceivedAt: typeof feed.lastReceivedAt === 'string' ? feed.lastReceivedAt : null };
  }
  if (typeof feed.lastReceivedAt !== 'string' || feed.lastReceivedAt.trim().length === 0) return undefined;
  return { status, lastReceivedAt: feed.lastReceivedAt };
}

function operationalStateOf(value: Record<string, unknown>, key: string): OperationalState | null | undefined {
  if (!(key in value) || value[key] == null) return null;
  const state = value[key];
  if (!isRecord(state) || typeof state.label !== 'string' || state.label.trim().length === 0) return undefined;
  if (typeof state.description !== 'string' || !state.description.startsWith(state.label)) return undefined;
  if (typeof state.unreliable !== 'boolean') return undefined;
  if (state.ingestedAt != null && typeof state.ingestedAt !== 'string') return undefined;
  return {
    label: state.label,
    description: state.description,
    unreliable: state.unreliable,
    ingestedAt: state.ingestedAt ?? null,
  };
}

function isVesselPosition(value: unknown): value is VesselPosition {
  return isRecord(value)
    && typeof value.mmsi === 'string'
    && value.mmsi.trim().length > 0
    && (value.vesselName == null || typeof value.vesselName === 'string')
    && isFiniteNumber(value.latitude)
    && isFiniteNumber(value.longitude)
    && isOptionalFiniteNumber(value.speedOverGroundKnots)
    && isOptionalFiniteNumber(value.courseOverGroundDegrees)
    && isOptionalFiniteNumber(value.trueHeadingDegrees)
    && typeof value.ingestedAt === 'string'
    && (value.source == null || value.source === 'AIS_STREAM' || value.source === 'SEEDED');
}

function asTracking(value: unknown): ShipmentTracking | null {
  if (!isRecord(value) || typeof value.motherVesselName !== 'string' || !isNullableText(value.feederVesselName)) return null;
  if (value.motherVessel != null && !isVesselPosition(value.motherVessel)) return null;
  if (value.feederVessel != null && !isVesselPosition(value.feederVessel)) return null;
  const motherVesselNameWarning = optionalIdentity(value, 'motherVesselNameWarning');
  const feederVesselNameWarning = optionalIdentity(value, 'feederVesselNameWarning');
  if (motherVesselNameWarning === undefined || feederVesselNameWarning === undefined) return null;
  return {
    motherVesselName: value.motherVesselName,
    motherVessel: value.motherVessel ?? null,
    motherVesselNameWarning,
    feederVesselName: value.feederVesselName,
    feederVessel: value.feederVessel ?? null,
    feederVesselNameWarning,
  };
}

function isTrackPoint(value: unknown): value is TrackPoint {
  return isRecord(value)
    && typeof value.id === 'number' && Number.isInteger(value.id) && value.id > 0
    && isFiniteNumber(value.latitude)
    && isFiniteNumber(value.longitude)
    && isOptionalFiniteNumber(value.speedOverGroundKnots)
    && isOptionalFiniteNumber(value.courseOverGroundDegrees)
    && isOptionalFiniteNumber(value.trueHeadingDegrees)
    && typeof value.ingestedAt === 'string';
}

function asTrack(value: unknown): VesselTrack | null {
  if (!isRecord(value) || (value.vessel !== 'MOTHER' && value.vessel !== 'FEEDER')) return null;
  if (!isNullableText(value.vesselName ?? null) || !isNullableText(value.mmsi ?? null)) return null;
  if (!isFiniteNumber(value.windowHours) || !isFiniteNumber(value.slowdownThresholdKnots) || !isFiniteNumber(value.pointLimit)) return null;
  if (typeof value.from !== 'string' || typeof value.to !== 'string' || typeof value.truncated !== 'boolean') return null;
  if (!Array.isArray(value.points) || !value.points.every(isTrackPoint)) return null;
  return {
    vessel: value.vessel,
    vesselName: (value.vesselName ?? null) as string | null,
    mmsi: (value.mmsi ?? null) as string | null,
    windowHours: value.windowHours,
    from: value.from,
    to: value.to,
    slowdownThresholdKnots: value.slowdownThresholdKnots,
    pointLimit: value.pointLimit,
    truncated: value.truncated,
    points: value.points.map(point => ({
      id: point.id,
      latitude: point.latitude,
      longitude: point.longitude,
      speedOverGroundKnots: point.speedOverGroundKnots ?? null,
      courseOverGroundDegrees: point.courseOverGroundDegrees ?? null,
      trueHeadingDegrees: point.trueHeadingDegrees ?? null,
      ingestedAt: point.ingestedAt,
    })),
  };
}

function asShipment(value: unknown): Shipment | null {
  if (!isRecord(value) || typeof value.id !== 'number') return null;
  const text = ['shipmentReference', 'origin', 'destination', 'transshipmentPort', 'motherVessel', 'plannedMotherArrivalAt', 'createdAt'] as const;
  if (!text.every(key => typeof value[key] === 'string')) return null;
  if (!isFiniteNumber(value.version) || value.version < 0) return null;
  if (!isNullableText(value.feederVessel) || !isNullableText(value.plannedFeederDepartureAt)) return null;
  const window = value.connectionWindow;
  if (window != null && !isConnectionWindow(window)) return null;
  const remainingConnectionWindow = remainingConnectionWindowOf(value);
  if (remainingConnectionWindow === undefined) return null;
  const risk = value.risk;
  if (risk != null && !isRisk(risk)) return null;
  const riskContributions = riskContributionsOf(value);
  if (riskContributions === undefined) return null;
  const motherVesselPosition = value.motherVesselPosition;
  const feederVesselPosition = value.feederVesselPosition;
  if (motherVesselPosition != null && !isVesselPosition(motherVesselPosition)) return null;
  if (feederVesselPosition != null && !isVesselPosition(feederVesselPosition)) return null;
  const importer = 'importer' in value ? organisationOf(value.importer) : null;
  if (importer === undefined) return null;
  const motherVesselImo = optionalIdentity(value, 'motherVesselImo');
  const motherVesselMmsi = optionalIdentity(value, 'motherVesselMmsi');
  const motherVesselOperator = optionalIdentity(value, 'motherVesselOperator');
  const feederVesselImo = optionalIdentity(value, 'feederVesselImo');
  const feederVesselMmsi = optionalIdentity(value, 'feederVesselMmsi');
  const feederVesselOperator = optionalIdentity(value, 'feederVesselOperator');
  const motherVesselNameWarning = optionalIdentity(value, 'motherVesselNameWarning');
  const feederVesselNameWarning = optionalIdentity(value, 'feederVesselNameWarning');
  if ([motherVesselImo, motherVesselMmsi, motherVesselOperator, feederVesselImo, feederVesselMmsi,
    feederVesselOperator, motherVesselNameWarning, feederVesselNameWarning].some(item => item === undefined)) return null;
  const motherOperationalState = operationalStateOf(value, 'motherOperationalState');
  const feederOperationalState = operationalStateOf(value, 'feederOperationalState');
  if (motherOperationalState === undefined || feederOperationalState === undefined) return null;
  const aisFeed = aisFeedOf(value);
  if (aisFeed === undefined) return null;
  const arrivalComparison = arrivalComparisonOf(value);
  if (arrivalComparison === undefined) return null;
  const updatedAt = optionalIdentity(value, 'updatedAt');
  const updatedByName = optionalIdentity(value, 'updatedByName');
  if (updatedAt === undefined || updatedByName === undefined) return null;
  const recommendation = recommendationOf(value, risk ?? null);
  if (recommendation === undefined) return null;
  const recommendationDecision = recommendationDecisionOf(value);
  if (recommendationDecision === undefined) return null;
  return {
    id: value.id,
    shipmentReference: value.shipmentReference as string,
    origin: value.origin as string,
    destination: value.destination as string,
    transshipmentPort: value.transshipmentPort as string,
    motherVessel: value.motherVessel as string,
    plannedMotherArrivalAt: value.plannedMotherArrivalAt as string,
    feederVessel: value.feederVessel,
    plannedFeederDepartureAt: value.plannedFeederDepartureAt,
    createdAt: value.createdAt as string,
    version: value.version,
    companyId: companyIdOf(value.companyId),
    connectionWindow: window ?? null,
    remainingConnectionWindow,
    risk: risk ?? null,
    riskContributions,
    status: shipmentStatusOf(value.status),
    motherVesselPosition: motherVesselPosition ?? null,
    feederVesselPosition: feederVesselPosition ?? null,
    importer,
    motherVesselImo,
    motherVesselMmsi,
    motherVesselOperator,
    feederVesselImo,
    feederVesselMmsi,
    feederVesselOperator,
    motherVesselNameWarning,
    feederVesselNameWarning,
    motherOperationalState,
    feederOperationalState,
    aisFeed,
    arrivalComparison,
    updatedAt,
    updatedByName,
    recommendation,
    recommendationDecision,
  };
}

const ARRIVAL_CLASSIFICATIONS: readonly ArrivalClassification[] = ['AHEAD_OF_PLAN', 'ON_SCHEDULE', 'BEHIND_PLAN'];
const ARRIVAL_FIELDS = ['estimatedArrivalAt', 'limitation', 'speedOverGroundKnots', 'classification', 'difference', 'differenceSeconds', 'differenceNanos', 'lastReceivedAt', 'possiblyOutdated'] as const;

function arrivalComparisonOf(value: Record<string, unknown>): ArrivalComparison | null | undefined {
  if (!('arrivalComparison' in value) || value.arrivalComparison == null) return null;
  return parseArrivalComparison(value.arrivalComparison);
}

function parseArrivalComparison(value: unknown): ArrivalComparison | undefined {
  if (!isRecord(value) || !ARRIVAL_FIELDS.every(field => field in value) || !('unavailableReason' in value)) return undefined;
  if (value.unavailableReason == null) return availableComparison(value);
  const reason = value.unavailableReason;
  if (!ARRIVAL_FIELDS.every(field => value[field] === null) || !isUnavailableReason(reason)) return undefined;
  return {
    estimatedArrivalAt: null,
    limitation: null,
    speedOverGroundKnots: null,
    classification: null,
    difference: null,
    differenceSeconds: null,
    differenceNanos: null,
    lastReceivedAt: null,
    possiblyOutdated: null,
    unavailableReason: { code: reason.code, message: reason.message },
  };
}

function availableComparison(value: Record<string, unknown>): ArrivalComparison | undefined {
  if (value.unavailableReason !== null || !isPresentText(value.estimatedArrivalAt) || !isPresentText(value.limitation)
    || !isFiniteNumber(value.speedOverGroundKnots) || !isArrivalClassification(value.classification)
    || !isPresentText(value.difference) || !isSignedSeconds(value.differenceSeconds) || !isNanos(value.differenceNanos)
    || !signsAgree(value.differenceSeconds, value.differenceNanos) || !isPresentText(value.lastReceivedAt)
    || typeof value.possiblyOutdated !== 'boolean') return undefined;
  return {
    estimatedArrivalAt: value.estimatedArrivalAt,
    limitation: value.limitation,
    speedOverGroundKnots: value.speedOverGroundKnots,
    classification: value.classification,
    difference: value.difference,
    differenceSeconds: value.differenceSeconds,
    differenceNanos: value.differenceNanos,
    lastReceivedAt: value.lastReceivedAt,
    possiblyOutdated: value.possiblyOutdated,
    unavailableReason: null,
  };
}

function isUnavailableReason(value: unknown): value is { code: string; message: string } {
  return isRecord(value) && isPresentText(value.code) && isPresentText(value.message);
}

function isArrivalClassification(value: unknown): value is ArrivalClassification {
  return ARRIVAL_CLASSIFICATIONS.some(classification => classification === value);
}

function isPresentText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isSignedSeconds(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value);
}

function isNanos(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && Math.abs(value) <= 999_999_999;
}

function signsAgree(seconds: number, nanos: number) {
  return seconds === 0 || nanos === 0 || Math.sign(seconds) === Math.sign(nanos);
}

function shipmentStatusOf(value: unknown): ShipmentStatus | null {
  return value === 'ACTIVE' || value === 'ARCHIVED' ? value : null;
}

function companyIdOf(value: unknown) {
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) return null;
  return value;
}

function optionalIdentity(record: Record<string, unknown>, key: string): string | null | undefined {
  if (!(key in record) || record[key] == null) return null;
  return typeof record[key] === 'string' ? record[key] : undefined;
}

function organisationOf(value: unknown): ImporterOrganisation | null | undefined {
  if (value == null) return null;
  if (!isRecord(value) || !isFiniteNumber(value.id) || value.id <= 0 || typeof value.code !== 'string' || !value.code.trim()
    || typeof value.name !== 'string' || !value.name.trim()) return undefined;
  return { id: value.id, code: value.code, name: value.name };
}

function asOrganisationPage(value: unknown): OrganisationPage | null {
  if (!isRecord(value) || !Array.isArray(value.items) || !isWhole(value.page) || !isWhole(value.size) || !isWhole(value.total)) return null;
  const items: ImporterOrganisation[] = [];
  for (const item of value.items) {
    const organisation = organisationOf(item);
    if (!organisation) return null;
    items.push(organisation);
  }
  return { items, page: value.page, size: value.size, total: value.total };
}

function isWhole(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function asShipmentImportJob(value: unknown): ShipmentImportJob | null {
  if (!isRecord(value) || typeof value.id !== 'number' || !isImportStatus(value.status)
    || typeof value.originalFilename !== 'string' || !isFiniteNumber(value.fileSizeBytes)
    || !isNonNegativeInteger(value.totalRows) || !isNonNegativeInteger(value.processedRows)
    || !isNonNegativeInteger(value.importedCount) || !isNonNegativeInteger(value.failedCount)
    || !isNullableText(value.failureMessage) || typeof value.createdAt !== 'string'
    || !isNullableText(value.startedAt) || !isNullableText(value.completedAt)) return null;
  return {
    id: value.id,
    status: value.status,
    originalFilename: value.originalFilename,
    fileSizeBytes: value.fileSizeBytes,
    totalRows: value.totalRows,
    processedRows: value.processedRows,
    importedCount: value.importedCount,
    failedCount: value.failedCount,
    failureMessage: value.failureMessage,
    createdAt: value.createdAt,
    startedAt: value.startedAt,
    completedAt: value.completedAt,
  };
}

function asShipmentImportError(value: unknown): ShipmentImportError | null {
  if (!isRecord(value) || !isNonNegativeInteger(value.rowNumber) || !isNullableText(value.column)
    || typeof value.code !== 'string' || typeof value.message !== 'string') return null;
  return { rowNumber: value.rowNumber, column: value.column, code: value.code, message: value.message };
}

function isImportStatus(value: unknown): value is ShipmentImportJobStatus {
  return value === 'PENDING' || value === 'PROCESSING' || value === 'COMPLETED' || value === 'FAILED';
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

export type ShipmentChangeType = 'CREATED' | 'UPDATED' | 'IMPORTER_LINKED' | 'ARCHIVED' | 'RESTORED';

export type ShipmentFieldChange = {
  field: string;
  previousValue: string | null;
  newValue: string | null;
};

export type ShipmentHistoryEntry = {
  id: number;
  changeType: ShipmentChangeType;
  changedBy: { id: number; fullName: string };
  recordedAt: string;
  changes: ShipmentFieldChange[];
};

export type ShipmentHistory = {
  shipmentId: number;
  earlierChangesUnrecorded: boolean;
  entries: ShipmentHistoryEntry[];
};

export async function getShipmentHistory(token: string, shipmentId: string): Promise<ShipmentHistory> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/history`, token);
  const history = asHistory(data);
  if (!history) throw new ApiError(UNEXPECTED, 502);
  return history;
}

const CHANGE_TYPES: readonly ShipmentChangeType[] = ['CREATED', 'UPDATED', 'IMPORTER_LINKED', 'ARCHIVED', 'RESTORED'];

function asHistory(value: unknown): ShipmentHistory | null {
  if (!isRecord(value) || !isWhole(value.shipmentId) || typeof value.earlierChangesUnrecorded !== 'boolean') return null;
  if (!Array.isArray(value.entries)) return null;
  const entries = value.entries.map(asHistoryEntry);
  if (entries.some(entry => entry === null)) return null;
  return { shipmentId: value.shipmentId, earlierChangesUnrecorded: value.earlierChangesUnrecorded, entries: entries as ShipmentHistoryEntry[] };
}

function asHistoryEntry(value: unknown): ShipmentHistoryEntry | null {
  if (!isRecord(value) || !isWhole(value.id) || typeof value.recordedAt !== 'string') return null;
  if (typeof value.changeType !== 'string' || !CHANGE_TYPES.includes(value.changeType as ShipmentChangeType)) return null;
  const actor = value.changedBy;
  if (!isRecord(actor) || !isWhole(actor.id) || typeof actor.fullName !== 'string') return null;
  if (!Array.isArray(value.changes) || !value.changes.every(isFieldChange)) return null;
  return {
    id: value.id,
    changeType: value.changeType as ShipmentChangeType,
    changedBy: { id: actor.id, fullName: actor.fullName },
    recordedAt: value.recordedAt,
    changes: value.changes.map(change => ({ field: change.field, previousValue: change.previousValue, newValue: change.newValue })),
  };
}

function isFieldChange(value: unknown): value is ShipmentFieldChange {
  return isRecord(value) && typeof value.field === 'string' && isNullableText(value.previousValue) && isNullableText(value.newValue);
}
