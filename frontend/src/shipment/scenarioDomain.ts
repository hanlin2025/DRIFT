import {
  apiInstantNanoseconds, decodeArrivalComparison, decodeDelayComparison, decodeMaritimeContext,
  decodeRemainingConnectionWindow, decodeShipmentRisk, isConnectionWindow, isVesselPosition,
  type ArrivalComparison, type ConnectionWindow, type DelayComparison, type MaritimeContext,
  type RemainingConnectionWindow, type ShipmentRisk, type TrackVessel, type VesselPosition,
} from './api';
import type { ScenarioSessionEnvelope, ScenarioShipmentBinding, ScenarioShipmentKey, ScenarioSnapshot, ScenarioStage } from './scenarioApi';

export type ReadonlyScenarioFact<T> = T extends object ? { readonly [K in keyof T]: ReadonlyScenarioFact<T[K]> } : T;
export type ScenarioConnectionWindowCalculation =
  | { readonly status: 'AVAILABLE'; readonly window: Readonly<ConnectionWindow> }
  | { readonly status: 'INCOMPLETE' | 'INVALID'; readonly window: null };

/** Obtain this identity from the accepted coordinator view and independently authorized
 * route/binding. This is association validation, never a replacement for server authorization. */
export type ScenarioDomainSelection = Readonly<{
  sessionId: string; epochId: string; snapshotId: string; revision: number;
  shipmentId: number; scenarioShipmentKey: ScenarioShipmentKey;
}>;
export type ScenarioPosition = Readonly<{
  mmsi: string; vesselName: string | null; latitude: number | null; longitude: number | null;
  speedOverGroundKnots: number | null; courseOverGroundDegrees: number | null; trueHeadingDegrees: number | null;
  navigationalStatus: number | null; positionValid: boolean | null; aisUtcSecond: number | null;
  ingestedAt: string; source: 'SEEDED';
}>;
export type DecodedScenarioObservation = Readonly<{
  vesselRole: TrackVessel; observedAtUtc: string; receivedAtUtc: string;
  sourceMode: 'SYNTHETIC_DEMONSTRATION'; position: ScenarioPosition;
  /** Null for false/unknown validity or missing coordinates. No invented map point. */
  usablePosition: Readonly<VesselPosition> | null;
}>;
export type DecodedScenarioShipment = Readonly<{
  scenarioShipmentKey: ScenarioShipmentKey; sourceShipmentId: number; scenarioShipmentReference: string;
  transshipmentPort: string; plannedMotherArrivalAt: string; plannedFeederDepartureAt: string;
  plannedConnectionWindow: ScenarioConnectionWindowCalculation;
  remainingConnectionWindow: Readonly<RemainingConnectionWindow>;
  arrivalEstimate: ReadonlyScenarioFact<ArrivalComparison>; risk: Readonly<ShipmentRisk>;
  businessImpact: Readonly<DelayComparison>;
}>;
export type DecodedScenarioSnapshot = ReadonlyScenarioFact<{
  sessionId: string; scenarioId: string; scenarioContractVersion: string;
  snapshotId: string; epochId: string; revision: number; stage: ScenarioStage;
  stageOrder: 0 | 1 | 2; stageCount: 3; fixtureStageId: string;
  simulationCutoffUtc: string; mapPlaybackCutoffUtc: string;
  bindings: readonly ScenarioShipmentBinding[]; selectedBinding: ScenarioShipmentBinding;
  shipments: readonly DecodedScenarioShipment[]; selectedShipment: DecodedScenarioShipment;
  aisObservations: readonly DecodedScenarioObservation[]; maritimeContext: MaritimeContext;
}>;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function text(value: unknown): value is string { return typeof value === 'string' && value.trim().length > 0; }
function instant(value: unknown): value is string { return apiInstantNanoseconds(value) !== undefined; }
function sameInstant(a: string | null, b: string | null): boolean {
  return a === null || b === null ? a === b : apiInstantNanoseconds(a) === apiInstantNanoseconds(b);
}
function beforeOrAt(a: string, b: string): boolean {
  const first = apiInstantNanoseconds(a), last = apiInstantNanoseconds(b);
  return first !== undefined && last !== undefined && first <= last;
}
function nullableNumber(value: unknown, minimum: number, maximum: number, integer = false): value is number | null {
  return value === null || (typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
    && (!integer || Number.isSafeInteger(value)));
}
export function decodeScenarioPlannedWindow(value: unknown): ScenarioConnectionWindowCalculation | undefined {
  if (!record(value)) return undefined;
  if (value.status === 'AVAILABLE' && isConnectionWindow(value.window)
    && Number.isSafeInteger(value.window.totalSeconds) && value.window.totalSeconds >= 0) {
    return { status: value.status, window: { ...value.window } };
  }
  if ((value.status === 'INCOMPLETE' || value.status === 'INVALID') && value.window === null) {
    return { status: value.status, window: null };
  }
  return undefined;
}
function decodePosition(value: unknown): ScenarioPosition | undefined {
  if (!record(value) || typeof value.mmsi !== 'string' || !/^[1-9]\d{8}$/.test(value.mmsi)
    || !(value.vesselName === null || text(value.vesselName)) || value.source !== 'SEEDED' || !instant(value.ingestedAt)
    || !nullableNumber(value.latitude, -90, 90) || !nullableNumber(value.longitude, -180, 180)
    || (value.latitude === null) !== (value.longitude === null)
    || !nullableNumber(value.speedOverGroundKnots, 0, Number.MAX_VALUE) || value.speedOverGroundKnots === 102.3
    || !nullableNumber(value.courseOverGroundDegrees, 0, 360) || value.courseOverGroundDegrees === 360
    || !nullableNumber(value.trueHeadingDegrees, 0, 359, true) || !nullableNumber(value.navigationalStatus, 0, 15, true)
    || !nullableNumber(value.aisUtcSecond, 0, 59, true)
    || !(value.positionValid === null || typeof value.positionValid === 'boolean')
    || (value.positionValid === true && value.latitude === null)) return undefined;
  return { mmsi: value.mmsi, vesselName: value.vesselName, latitude: value.latitude, longitude: value.longitude,
    speedOverGroundKnots: value.speedOverGroundKnots, courseOverGroundDegrees: value.courseOverGroundDegrees,
    trueHeadingDegrees: value.trueHeadingDegrees, navigationalStatus: value.navigationalStatus,
    positionValid: value.positionValid, aisUtcSecond: value.aisUtcSecond, ingestedAt: value.ingestedAt, source: value.source };
}
export function decodeScenarioObservation(value: unknown, cutoffUtc: string): DecodedScenarioObservation | undefined {
  if (!record(value) || (value.vesselRole !== 'MOTHER' && value.vesselRole !== 'FEEDER')
    || value.sourceMode !== 'SYNTHETIC_DEMONSTRATION' || !instant(value.observedAtUtc) || !instant(value.receivedAtUtc)) return undefined;
  const position = decodePosition(value.position);
  if (!position || !beforeOrAt(value.observedAtUtc, value.receivedAtUtc) || !beforeOrAt(value.receivedAtUtc, position.ingestedAt)
    || !beforeOrAt(position.ingestedAt, cutoffUtc)) return undefined;
  // The ordinary guard validates the compatible projection, but never promotes false/null validity.
  const usablePosition = position.positionValid === true && isVesselPosition(position) ? {
    mmsi: position.mmsi, vesselName: position.vesselName, latitude: position.latitude, longitude: position.longitude,
    speedOverGroundKnots: position.speedOverGroundKnots, courseOverGroundDegrees: position.courseOverGroundDegrees,
    trueHeadingDegrees: position.trueHeadingDegrees, ingestedAt: position.ingestedAt, source: position.source,
  } : null;
  return { vesselRole: value.vesselRole, sourceMode: value.sourceMode, observedAtUtc: value.observedAtUtc,
    receivedAtUtc: value.receivedAtUtc, position, usablePosition };
}
function decodeShipment(value: ScenarioSnapshot['shipments'][number], binding: ScenarioShipmentBinding,
  mother: DecodedScenarioObservation): DecodedScenarioShipment | undefined {
  if (value.scenarioShipmentKey !== binding.scenarioShipmentKey || value.sourceShipmentId !== binding.shipmentId
    || value.scenarioShipmentReference !== binding.scenarioShipmentReference || !text(value.transshipmentPort)
    || !instant(value.plannedMotherArrivalAt) || !instant(value.plannedFeederDepartureAt)) return undefined;
  const planned = decodeScenarioPlannedWindow(value.plannedConnectionWindow);
  const remaining = decodeRemainingConnectionWindow(value.remainingConnectionWindow);
  const arrival = decodeArrivalComparison(value.arrivalEstimate);
  const risk = decodeShipmentRisk(value.risk);
  const impact = decodeDelayComparison(value.businessImpact);
  if (!planned || !remaining || !arrival || !risk || !impact) return undefined;
  if (arrival.unavailableReason === null && (!sameInstant(arrival.lastReceivedAt, mother.position.ingestedAt)
    || arrival.speedOverGroundKnots !== mother.position.speedOverGroundKnots)) return undefined;
  if (!sameInstant(impact.estimatedArrivalAt, arrival.estimatedArrivalAt)) return undefined;
  return { scenarioShipmentKey: value.scenarioShipmentKey, sourceShipmentId: value.sourceShipmentId,
    scenarioShipmentReference: value.scenarioShipmentReference, transshipmentPort: value.transshipmentPort,
    plannedMotherArrivalAt: value.plannedMotherArrivalAt, plannedFeederDepartureAt: value.plannedFeederDepartureAt,
    plannedConnectionWindow: planned, remainingConnectionWindow: remaining, arrivalEstimate: arrival, risk, businessImpact: impact };
}
function contextAgrees(context: MaritimeContext, cutoff: string, observations: readonly DecodedScenarioObservation[]): boolean {
  if (context.contractVersion !== 'cdg246.maritime-evidence-bundle.v1' || !context.enabled
    || !sameInstant(context.assessmentCutoffUtc, cutoff) || new Set(context.evidence.map(row => row.evidenceId)).size !== context.evidence.length
    || new Set(context.notices.map(row => row.noticeId)).size !== context.notices.length) return false;
  // The pinned runtime emits an empty/unavailable bundle at baseline, otherwise synthetic evidence.
  if (context.sourceMode !== (context.evidence.length ? 'SYNTHETIC_DEMONSTRATION' : 'UNAVAILABLE')
    || context.shipmentAssociationConfidence !== (context.evidence.length ? 'VERIFIED' : 'UNAVAILABLE')) return false;
  for (const row of context.evidence) {
    if (![row.activityCompatibility, row.locationCompatibility, row.timeCompatibility].every(value => ['COMPATIBLE', 'INCOMPATIBLE', 'UNKNOWN'].includes(value))
      || row.sourceMode !== 'SYNTHETIC_DEMONSTRATION' || row.shipmentAssociationConfidence !== 'VERIFIED' || !row.humanReviewRequired
      || row.retrievedAtUtc === null || !beforeOrAt(row.retrievedAtUtc, cutoff)
      || (row.observedAt !== null && (!beforeOrAt(row.observedAt, row.retrievedAtUtc) || !beforeOrAt(row.observedAt, cutoff)))
      || !['FRESH', 'STALE', 'UNKNOWN'].includes(row.freshness)
      || (row.freshness !== 'UNKNOWN' && row.observedAt === null)
      || (row.freshness === 'UNKNOWN' && row.evidenceSufficient)) return false;
    // A source ETA can be in the future; an observation/publication cannot. Do not rebase either.
    if (row.sourceEventAt !== null && row.sourceType !== 'MPA_ETA_REVISION' && !beforeOrAt(row.sourceEventAt, cutoff)) return false;
    if (row.sourceType === 'MPA_ETA_REVISION') {
      const observation = observations.find(item => item.vesselRole === row.vesselRole);
      if (row.associationScope !== 'VESSEL_OBSERVATION' || row.observationType !== 'EXPECTED_ARRIVAL' || !observation
        || !text(row.vesselIdentity) || (row.vesselIdentity !== observation.position.vesselName && row.vesselIdentity !== observation.position.mmsi)) return false;
    } else if (row.vesselRole != null || row.vesselIdentity != null || row.observationType != null
      || row.associationScope !== (row.sourceType === 'MPA_OFFICIAL_NOTICE' ? 'PORT_CONTEXT' : 'REGIONAL_CONTEXT')) return false;
  }
  const noticeEvidence = context.evidence.filter(row => row.sourceType === 'MPA_OFFICIAL_NOTICE');
  if (noticeEvidence.length !== context.notices.length) return false;
  return context.notices.every(notice => {
    const row = noticeEvidence.find(item => item.evidenceId === notice.noticeId);
    return row !== undefined && notice.effectiveFrom === null && notice.effectiveUntil === null && notice.supersessionStatus === 'UNKNOWN'
      && notice.title === row.title && notice.officialSourceUrl === row.sourceUrl && notice.citation === row.citation
      && sameInstant(notice.publishedAt, row.sourceEventAt) && sameInstant(notice.observedAt, row.observedAt)
      && (notice.publishedAt === null || beforeOrAt(notice.publishedAt, cutoff))
      && notice.compatibilityState === row.compatibilityState && notice.evidenceSufficient === row.evidenceSufficient
      && notice.humanReviewRequired === row.humanReviewRequired && notice.shipmentAssociationConfidence === row.shipmentAssociationConfidence
      && notice.limitation === row.limitation && notice.activityEvidence.length === 1 && notice.activityEvidence[0] === row.activityCompatibility
      && notice.locationEvidence.length === 1 && notice.locationEvidence[0] === row.locationCompatibility;
  });
}
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
}
const STAGES = {
  BASELINE: { order: 0, fixture: 'baseline' }, CHANGE: { order: 1, fixture: 'material-slowdown' },
  WORSENED: { order: 2, fixture: 'worsened-connection' },
};

/** Decode ONE already transport-validated, committed envelope against the selected identity.
 * Undefined means unavailable/invalid as a whole (also UNSTARTED); no partial or live fallback.
 * The returned clone is deeply frozen; the coordinator's envelope is never mutated.
 * This boundary validates facts, not freshness ordering of asynchronous responses or auth. */
export function decodeScenarioSnapshot(envelope: ScenarioSessionEnvelope, selection: ScenarioDomainSelection): DecodedScenarioSnapshot | undefined {
  const snapshot = envelope.snapshot;
  if (!snapshot || !envelope.capabilities.canRead || envelope.sessionId !== selection.sessionId || envelope.epochId !== selection.epochId
    || snapshot.epochId !== envelope.epochId || snapshot.snapshotId !== selection.snapshotId || snapshot.revision !== envelope.revision
    || envelope.revision !== selection.revision || snapshot.stageCount !== 3 || snapshot.stageOrder !== STAGES[snapshot.stage]?.order
    || snapshot.fixtureStageId !== STAGES[snapshot.stage]?.fixture || !instant(snapshot.simulationCutoffUtc)
    || !instant(snapshot.mapPlaybackCutoffUtc) || !sameInstant(snapshot.simulationCutoffUtc, snapshot.mapPlaybackCutoffUtc)) return undefined;
  const binding = envelope.shipments.find(row => row.shipmentId === selection.shipmentId && row.scenarioShipmentKey === selection.scenarioShipmentKey);
  if (!binding || envelope.shipments.length !== 2 || new Set(envelope.shipments.map(row => row.scenarioShipmentKey)).size !== 2
    || new Set(envelope.shipments.map(row => row.shipmentId)).size !== 2 || snapshot.shipments.length !== 2 || snapshot.aisObservations.length !== 2) return undefined;
  const observations = snapshot.aisObservations.map(row => decodeScenarioObservation(row, snapshot.simulationCutoffUtc));
  const mother = observations.find(row => row?.vesselRole === 'MOTHER'), feeder = observations.find(row => row?.vesselRole === 'FEEDER');
  if (!mother || !feeder || mother.position.mmsi === feeder.position.mmsi) return undefined;
  const shipments: DecodedScenarioShipment[] = [];
  for (const row of snapshot.shipments) {
    const bound = envelope.shipments.find(item => item.scenarioShipmentKey === row.scenarioShipmentKey);
    if (!bound) return undefined;
    const decoded = decodeShipment(row, bound, mother);
    if (!decoded) return undefined;
    shipments.push(decoded);
  }
  if (new Set(shipments.map(row => row.scenarioShipmentKey)).size !== 2) return undefined;
  const [a, b] = shipments;
  if (a.transshipmentPort !== b.transshipmentPort || !sameInstant(a.plannedMotherArrivalAt, b.plannedMotherArrivalAt)
    || !sameInstant(a.plannedFeederDepartureAt, b.plannedFeederDepartureAt)) return undefined;
  const selected = shipments.find(row => row.scenarioShipmentKey === binding.scenarioShipmentKey);
  const context = decodeMaritimeContext(snapshot.maritimeContext);
  if (!selected || !context || !contextAgrees(context, snapshot.simulationCutoffUtc, [mother, feeder])) return undefined;
  return freeze(structuredClone({ sessionId: envelope.sessionId, scenarioId: envelope.scenarioId,
    scenarioContractVersion: envelope.scenarioContractVersion, snapshotId: snapshot.snapshotId, epochId: snapshot.epochId,
    revision: snapshot.revision, stage: snapshot.stage, stageOrder: snapshot.stageOrder, stageCount: snapshot.stageCount,
    fixtureStageId: snapshot.fixtureStageId, simulationCutoffUtc: snapshot.simulationCutoffUtc, mapPlaybackCutoffUtc: snapshot.mapPlaybackCutoffUtc,
    bindings: envelope.shipments, selectedBinding: binding, shipments, selectedShipment: selected,
    aisObservations: [mother, feeder], maritimeContext: context }));
}
