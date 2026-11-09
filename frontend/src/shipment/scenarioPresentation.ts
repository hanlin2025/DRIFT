import type { Shipment, TrackVessel, TrackWindow } from './api';
import { locate } from './places';
import type { DecodedScenarioObservation, DecodedScenarioSnapshot } from './scenarioDomain';
import type { ScenarioLifecycleState, ScenarioShipmentKey } from './scenarioApi';
import type { TrackAuthorizationContext, TrackDisplayMode, TrackPresentationData } from './trackPresentationContract';
import { DEFAULT_TRACK_WINDOW } from './trackSegments';

/**
 * Current viewer/route/session values supplied by the host and coordinator. `authorization` must
 * reflect server-authorized session access and the independent authorized detail read; this pure
 * adapter does not establish authorization itself.
 */
export type ScenarioPresentationContext = Readonly<{
  authorization: 'authorized' | 'denied';
  viewerId: number | string | null;
  companyId: number | null;
  /** Nonsecret generation that changes when the authenticated context changes. Never a token. */
  authContextGeneration: string | null;
  routeShipmentId: number | null;
  scenarioShipmentKey: ScenarioShipmentKey | null;
  sessionId: string | null;
  epochId: string | null;
  snapshotId: string | null;
  revision: number | null;
  lifecycleState: ScenarioLifecycleState | null;
}>;

/** Only the synthetic scenario view with no history cursor is supported by this adapter. */
export type ScenarioPresentationView = Readonly<{
  displayMode: TrackDisplayMode;
  selectedRole: TrackVessel;
  window: TrackWindow;
  selectedPointId: number | null;
  recordedAisCutoff: string | null;
}>;

function hasText(value: string | null): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function validPositiveId(value: number | null): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function validViewerId(value: number | string | null): value is number | string {
  return (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
    || (typeof value === 'string' && value.trim().length > 0);
}

function createScopeId(context: ScenarioPresentationContext): TrackAuthorizationContext['scopeId'] {
  // Deliberately excludes route/session IDs and credentials. The host rotates this generation
  // when the authenticated viewer context changes, including token replacement.
  return JSON.stringify(['controlled-scenario-v1', context.viewerId, context.companyId, context.authContextGeneration]);
}

function sameAnchorBinding(snapshot: DecodedScenarioSnapshot, anchor: Shipment, context: ScenarioPresentationContext): boolean {
  const binding = snapshot.selectedBinding;
  const selected = snapshot.selectedShipment;
  return context.routeShipmentId === anchor.id
    && context.scenarioShipmentKey === binding.scenarioShipmentKey
    && binding.shipmentId === anchor.id
    && binding.shipmentReference === anchor.shipmentReference
    && selected.scenarioShipmentKey === binding.scenarioShipmentKey
    && selected.sourceShipmentId === binding.shipmentId
    && selected.scenarioShipmentReference === binding.scenarioShipmentReference
    && snapshot.bindings.some(candidate => candidate.scenarioShipmentKey === binding.scenarioShipmentKey
      && candidate.shipmentId === anchor.id
      && candidate.shipmentReference === anchor.shipmentReference
      && candidate.scenarioShipmentReference === binding.scenarioShipmentReference);
}

function sessionContextAgrees(snapshot: DecodedScenarioSnapshot, context: ScenarioPresentationContext): boolean {
  const expectedLifecycle: ScenarioLifecycleState = snapshot.stage === 'WORSENED' ? 'COMPLETE' : 'ACTIVE';
  return context.authorization === 'authorized'
    && validViewerId(context.viewerId)
    && validPositiveId(context.companyId)
    && hasText(context.authContextGeneration)
    && validPositiveId(context.routeShipmentId)
    && (context.scenarioShipmentKey === 'A' || context.scenarioShipmentKey === 'B')
    && hasText(context.sessionId)
    && hasText(context.epochId)
    && hasText(context.snapshotId)
    && typeof context.revision === 'number' && Number.isSafeInteger(context.revision) && context.revision > 0
    && context.lifecycleState === expectedLifecycle
    && context.sessionId === snapshot.sessionId
    && context.epochId === snapshot.epochId
    && context.snapshotId === snapshot.snapshotId
    && context.revision === snapshot.revision;
}

function copyObservationPosition(observation: DecodedScenarioObservation) {
  const position = observation.usablePosition;
  return position === null ? null : {
    mmsi: position.mmsi,
    vesselName: position.vesselName,
    latitude: position.latitude,
    longitude: position.longitude,
    speedOverGroundKnots: position.speedOverGroundKnots,
    courseOverGroundDegrees: position.courseOverGroundDegrees,
    trueHeadingDegrees: position.trueHeadingDegrees,
    ingestedAt: position.ingestedAt,
    source: position.source,
  };
}

function rolePresentation(observation: DecodedScenarioObservation) {
  return {
    // CDG-287 renders this as an authorized AIS source shipment. The scenario anchor is only
    // navigation context, so keep this null until the presentation can label that distinction.
    sourceShipmentId: null,
    captureId: null,
    mappingVersion: null,
    identity: {
      name: observation.position.vesselName,
      imo: null,
      mmsi: observation.position.mmsi,
    },
    position: copyObservationPosition(observation),
    history: null,
    state: observation.usablePosition === null ? 'unavailable' as const : 'available' as const,
  };
}

function projectShipment(anchor: Shipment, snapshot: DecodedScenarioSnapshot, mother: DecodedScenarioObservation,
  feeder: DecodedScenarioObservation): Shipment {
  const facts = snapshot.selectedShipment;
  return {
    // The real identifier/reference remain navigation context. This object is display-only and
    // must never be sent to updateShipment or used as a mutation payload.
    id: anchor.id,
    shipmentReference: anchor.shipmentReference,
    companyId: anchor.companyId,
    createdAt: anchor.createdAt,
    version: anchor.version,

    // The scenario contract provides a synthetic transshipment port and schedules only.
    // It does not bind the anchor's real origin/destination to this fictional voyage.
    origin: 'Not recorded',
    destination: 'Not recorded',
    transshipmentPort: facts.transshipmentPort,
    motherVessel: mother.position.vesselName ?? 'Not recorded',
    plannedMotherArrivalAt: facts.plannedMotherArrivalAt,
    feederVessel: feeder.position.vesselName,
    plannedFeederDepartureAt: facts.plannedFeederDepartureAt,

    // Scenario-owned calculations only. Live operational fields are intentionally omitted or null.
    connectionWindow: facts.plannedConnectionWindow.status === 'AVAILABLE'
      ? { ...facts.plannedConnectionWindow.window } : null,
    remainingConnectionWindow: { ...facts.remainingConnectionWindow },
    risk: { ...facts.risk },
    arrivalComparison: { ...facts.arrivalEstimate },
    delayComparison: { ...facts.businessImpact },
    status: null,
    motherVesselPosition: null,
    feederVesselPosition: null,
    motherVesselMmsi: mother.position.mmsi,
    motherVesselImo: null,
    feederVesselMmsi: feeder.position.mmsi,
    feederVesselImo: null,
    motherOperationalState: null,
    feederOperationalState: null,
    aisFeed: null,
  };
}

function freezeTree(value: unknown): void {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return;
  for (const key of Reflect.ownKeys(value)) freezeTree(Reflect.get(value, key));
  Object.freeze(value);
}

/**
 * Pure display adapter from one accepted, decoded scenario snapshot to CDG-287's exact map contract.
 * `null` means there is no active protected presentation; the host must unmount it or show its
 * authorized empty state. In particular, reset/UNSTARTED and any authorization/context mismatch
 * never retain a previous snapshot, identity, position, or history.
 *
 * The independently authorized anchor supplies navigation identity only. Its route, AIS, schedule,
 * risk, ETA, deadline, recommendation, and importer facts are not copied. Synthetic position
 * provenance remains in the caller-owned DecodedScenarioSnapshot; only its compatible SEEDED
 * VesselPosition projection and original ingestedAt reach TrackPresentationData. The map contract has no separate
 * observedAtUtc/receivedAtUtc fields, so the host must retain the decoded snapshot for those typed facts. The
 * scenario reference uses simulationCutoffUtc; mapPlaybackCutoffUtc stays on the input and never becomes an
 * authentic recordedAisCutoff.
 *
 * Integration event ownership: Refresh calls coordinator.refresh; role selection is local state;
 * unsupported window/history selection and recorded mode resolve to unavailable with no request;
 * live mode exits controlled viewing explicitly; Start/Next/Reset call only the scenario coordinator.
 * This adapter wires no handlers, requests, timers, or stage transitions.
 */
export function adaptScenarioSnapshotToTrackPresentation(
  snapshot: DecodedScenarioSnapshot | null,
  authorizedAnchor: Shipment | null,
  context: ScenarioPresentationContext | null,
  view: ScenarioPresentationView,
): TrackPresentationData | null {
  if (context?.authorization === 'denied') return null;
  if (!snapshot || !authorizedAnchor || !context || context.lifecycleState === 'UNSTARTED' || context.snapshotId === null) return null;
  if (!sessionContextAgrees(snapshot, context) || !sameAnchorBinding(snapshot, authorizedAnchor, context)) return null;

  if (view.displayMode !== 'scenario' || view.window !== DEFAULT_TRACK_WINDOW
    || view.selectedPointId !== null || view.recordedAisCutoff !== null
    || (view.selectedRole !== 'MOTHER' && view.selectedRole !== 'FEEDER')) return null;

  const mother = snapshot.aisObservations.find(observation => observation.vesselRole === 'MOTHER');
  const feeder = snapshot.aisObservations.find(observation => observation.vesselRole === 'FEEDER');
  if (!mother || !feeder) return null;

  const shipment = projectShipment(authorizedAnchor, snapshot, mother, feeder);
  const geometry = [locate(shipment.origin), locate(shipment.transshipmentPort), locate(shipment.destination)];
  const routeKey = `scenario-route-v1:${authorizedAnchor.id}:${JSON.stringify(geometry)}`;
  const authorization: TrackAuthorizationContext = {
    scopeId: createScopeId(context),
    sessionId: snapshot.sessionId,
    epoch: snapshot.epochId,
    snapshotId: snapshot.snapshotId,
    revision: String(snapshot.revision),
  };
  const presentation: TrackPresentationData = {
    shipment,
    routeKey,
    roles: {
      MOTHER: rolePresentation(mother),
      FEEDER: rolePresentation(feeder),
    },
    displayMode: 'scenario',
    selectedRole: view.selectedRole,
    window: DEFAULT_TRACK_WINDOW,
    selectedPointId: null,
    scenarioReferenceCutoff: snapshot.simulationCutoffUtc,
    recordedAisCutoff: null,
    context: authorization,
  };
  freezeTree(presentation);
  return presentation;
}
