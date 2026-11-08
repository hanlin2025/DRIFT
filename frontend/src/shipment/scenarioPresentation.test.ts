import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Shipment } from './api';
import {
  decodeScenarioSnapshot,
  type ScenarioDomainSelection,
  type ScenarioPosition,
} from './scenarioDomain';
import type {
  ScenarioDomainPayload,
  ScenarioLifecycleState,
  ScenarioSessionEnvelope,
  ScenarioShipmentBinding,
  ScenarioShipmentKey,
  ScenarioShipmentRead,
  ScenarioStage,
} from './scenarioApi';
import {
  adaptScenarioSnapshotToTrackPresentation,
  type ScenarioPresentationContext,
  type ScenarioPresentationView,
} from './scenarioPresentation';

// Compact v1 fixtures use the pinned CDG-288 backend stage IDs, cutoffs, positions, schedules,
// and calculation outputs. Each envelope is decoded before it reaches the presentation adapter.
const SESSION_ID = 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf';
const EPOCH_ID = '74dab1c6-1d4b-4889-8dde-0b31986fed28';
const SNAPSHOT_IDS = [
  '063052b2-fca1-4116-b559-f387ac6c2dc0',
  '163052b2-fca1-4116-b559-f387ac6c2dc1',
  '263052b2-fca1-4116-b559-f387ac6c2dc2',
] as const;
const BINDINGS: readonly ScenarioShipmentBinding[] = [
  { scenarioShipmentKey: 'A', shipmentId: 142, shipmentReference: 'DEMO-A-PORTFOLIO', scenarioShipmentReference: 'DEMO-CDG279-Api-A' },
  { scenarioShipmentKey: 'B', shipmentId: 143, shipmentReference: 'DEMO-B-PORTFOLIO', scenarioShipmentReference: 'DEMO-CDG279-Api-B' },
];

const STAGE_FACTS = {
  BASELINE: {
    order: 0, fixture: 'baseline', cutoff: '2026-10-01T00:00:00Z', speed: 10,
    arrival: '2026-10-01T06:00:00Z', classification: 'ON_SCHEDULE', difference: 'On schedule · estimated at the planned time', differenceSeconds: 0,
    remaining: 108000, risk: 'LOW', riskExplanation: "At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
  },
  CHANGE: {
    order: 1, fixture: 'material-slowdown', cutoff: '2026-10-01T04:00:00Z', speed: 3,
    arrival: '2026-10-01T16:00:00Z', classification: 'BEHIND_PLAN', difference: 'Behind plan · estimated 10h later than planned', differenceSeconds: 36000,
    remaining: 72000, risk: 'MEDIUM', riskExplanation: "Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
  },
  WORSENED: {
    order: 2, fixture: 'worsened-connection', cutoff: '2026-10-01T08:00:00Z', speed: 1,
    arrival: '2026-10-02T08:00:00Z', classification: 'BEHIND_PLAN', difference: 'Behind plan · estimated 26h later than planned', differenceSeconds: 93600,
    remaining: 14400, risk: 'HIGH', riskExplanation: "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
  },
} as const;

const view = (overrides: Partial<ScenarioPresentationView> = {}): ScenarioPresentationView => ({
  displayMode: 'scenario', selectedRole: 'MOTHER', window: 24, selectedPointId: null, recordedAisCutoff: null, ...overrides,
});

type EnvelopeOptions = Readonly<{
  motherPosition?: Partial<ScenarioPosition>;
  feederPosition?: Partial<ScenarioPosition>;
  transshipmentPort?: string;
  epochId?: string;
  revision?: number;
  snapshotId?: string;
}>;

function position(role: 'MOTHER' | 'FEEDER', stage: ScenarioStage, overrides: Partial<ScenarioPosition> = {}): ScenarioPosition {
  const data = STAGE_FACTS[stage];
  const mother = role === 'MOTHER';
  return {
    mmsi: mother ? '990279001' : '990279002',
    vesselName: mother ? 'CDG279 SYNTHETIC MOTHER Api' : 'CDG279 SYNTHETIC FEEDER Api',
    latitude: mother ? (stage === 'BASELINE' ? 2.26 : stage === 'CHANGE' ? 1.86 : 1.66) : 1.26,
    longitude: 103.82,
    speedOverGroundKnots: mother ? data.speed : 0,
    courseOverGroundDegrees: mother ? 180 : null,
    trueHeadingDegrees: mother && stage === 'BASELINE' ? 180 : null,
    navigationalStatus: mother ? 0 : 5,
    positionValid: true,
    aisUtcSecond: 0,
    ingestedAt: data.cutoff,
    source: 'SEEDED',
    ...overrides,
  };
}

function scenarioEvidence(stage: ScenarioStage, cutoff: string, vesselName: string): ScenarioDomainPayload[] {
  if (stage === 'BASELINE') return [];
  return [{
    sourceType: 'MPA_ETA_REVISION',
    sourceMode: 'SYNTHETIC_DEMONSTRATION',
    evidenceId: 'cdg279-v1-revision-1',
    title: 'Synthetic expected-arrival revision',
    sourceIdentifier: 'CDG279-SYNTHETIC-MPA-REVISION-1',
    sourceUrl: 'https://example.invalid/cdg279/revision-1',
    citation: 'Synthetic MPA-style revision 1; the record is fictional and is not an authoritative vessel ETA.',
    sourceEventAt: null,
    observedAt: cutoff,
    retrievedAtUtc: cutoff,
    freshness: 'FRESH',
    previousValue: '2026-10-01T06:00:00Z',
    currentValue: '2026-10-01T15:00:00Z',
    changeMagnitude: null,
    activityCompatibility: 'COMPATIBLE',
    locationCompatibility: 'COMPATIBLE',
    timeCompatibility: 'COMPATIBLE',
    compatibilityState: 'COMPATIBLE',
    evidenceSufficient: true,
    humanReviewRequired: true,
    shipmentAssociationConfidence: 'VERIFIED',
    limitation: 'The revised expected-arrival value is fictional and deliberately differs from the AIS-derived estimate; it does not replace or change risk. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.',
    vesselIdentity: vesselName,
    vesselRole: 'MOTHER',
    observationType: 'EXPECTED_ARRIVAL',
    associationScope: 'VESSEL_OBSERVATION',
  }];
}

function shipmentRead(key: ScenarioShipmentKey, stage: ScenarioStage, transshipmentPort: string): ScenarioShipmentRead {
  const data = STAGE_FACTS[stage];
  const binding = BINDINGS.find(row => row.scenarioShipmentKey === key)!;
  const deadline = key === 'A' ? '2026-10-01T12:00:00Z' : '2026-10-02T10:00:00Z';
  const exceeds = key === 'A' && stage !== 'BASELINE';
  return {
    scenarioShipmentKey: key,
    sourceShipmentId: binding.shipmentId,
    scenarioShipmentReference: binding.scenarioShipmentReference,
    transshipmentPort,
    plannedMotherArrivalAt: '2026-10-01T06:00:00Z',
    plannedFeederDepartureAt: '2026-10-02T12:00:00Z',
    plannedConnectionWindow: { status: 'AVAILABLE', window: { duration: '1 day 6 hours', totalSeconds: 108000 } },
    remainingConnectionWindow: {
      status: 'AVAILABLE', duration: data.remaining === 108000 ? '1 day 6 hours' : data.remaining === 72000 ? '20 hours' : '4 hours',
      totalSeconds: data.remaining, missedBy: null, missedBySeconds: null,
    },
    arrivalEstimate: {
      estimatedArrivalAt: data.arrival,
      limitation: 'Approximate arrival — based on straight-line distance and current speed.',
      speedOverGroundKnots: data.speed,
      classification: data.classification,
      difference: data.difference,
      differenceSeconds: data.differenceSeconds,
      differenceNanos: 0,
      lastReceivedAt: data.cutoff,
      possiblyOutdated: false,
      unavailableReason: null,
    },
    risk: { level: data.risk, explanation: data.riskExplanation },
    businessImpact: {
      outcome: exceeds ? 'EXCEEDS_DEADLINE' : 'NO_PREDICTED_IMPACT',
      reason: null,
      message: exceeds ? 'The predicted arrival exceeds the recorded mother-vessel transshipment-arrival deadline.'
        : 'No predicted impact on the recorded mother-vessel transshipment-arrival deadline.',
      estimatedArrivalAt: data.arrival,
      deadline,
      recordedTransshipmentPort: transshipmentPort,
    },
  };
}

function sourceEnvelope(stage: ScenarioStage, options: EnvelopeOptions = {}): ScenarioSessionEnvelope {
  const data = STAGE_FACTS[stage];
  const epochId = options.epochId ?? EPOCH_ID;
  const revision = options.revision ?? data.order + 1;
  const snapshotId = options.snapshotId ?? SNAPSHOT_IDS[data.order];
  const transshipmentPort = options.transshipmentPort ?? 'Synthetic Multi-source Port';
  const motherName = 'CDG279 SYNTHETIC MOTHER Api';
  const mother = position('MOTHER', stage, options.motherPosition);
  const feeder = position('FEEDER', stage, options.feederPosition);
  const rows: ScenarioShipmentRead[] = [
    shipmentRead('A', stage, transshipmentPort),
    shipmentRead('B', stage, transshipmentPort),
  ];
  const maritimeContext: ScenarioDomainPayload = {
    contractVersion: 'cdg246.maritime-evidence-bundle.v1',
    enabled: true,
    sourceMode: stage === 'BASELINE' ? 'UNAVAILABLE' : 'SYNTHETIC_DEMONSTRATION',
    assessmentCutoffUtc: data.cutoff,
    shipmentAssociationConfidence: stage === 'BASELINE' ? 'UNAVAILABLE' : 'VERIFIED',
    limitation: 'Human review is required. This bundle is a read-only replay/demo and does not change ETA, shipment, risk, alerts, or operational decisions.',
    evidence: scenarioEvidence(stage, data.cutoff, motherName),
    notices: [],
    aiExplanation: null,
    suggestedActions: null,
  };
  const snapshot = {
    snapshotId,
    epochId,
    revision,
    stage,
    stageOrder: data.order,
    stageCount: 3 as const,
    fixtureStageId: data.fixture,
    simulationCutoffUtc: data.cutoff,
    mapPlaybackCutoffUtc: data.cutoff,
    shipments: rows,
    aisObservations: [
      { vesselRole: 'MOTHER' as const, observedAtUtc: data.cutoff, receivedAtUtc: data.cutoff, sourceMode: 'SYNTHETIC_DEMONSTRATION', position: mother },
      { vesselRole: 'FEEDER' as const, observedAtUtc: data.cutoff, receivedAtUtc: data.cutoff, sourceMode: 'SYNTHETIC_DEMONSTRATION', position: feeder },
    ],
    maritimeContext,
  };
  const base = {
    apiVersion: '1' as const,
    sessionId: SESSION_ID,
    scenarioId: 'CDG279-Api',
    scenarioContractVersion: 'cdg279.synthetic-disruption.v1' as const,
    epochId,
    revision,
    capabilities: {
      canRead: true, canStart: false, canAdvance: stage !== 'WORSENED', canReset: true, canGenerateAssessment: false as const,
    },
    shipments: BINDINGS,
  };
  return stage === 'WORSENED'
    ? { ...base, state: 'COMPLETE', snapshot }
    : { ...base, state: 'ACTIVE', snapshot };
}

function decoded(stage: ScenarioStage, key: ScenarioShipmentKey = 'A', options: EnvelopeOptions = {}) {
  const envelope = sourceEnvelope(stage, options);
  const binding = envelope.shipments.find(row => row.scenarioShipmentKey === key);
  const snapshot = envelope.snapshot;
  if (!binding || !snapshot) throw new Error('Test fixture is incomplete.');
  const selection: ScenarioDomainSelection = {
    sessionId: envelope.sessionId,
    epochId: envelope.epochId,
    snapshotId: snapshot.snapshotId,
    revision: envelope.revision,
    shipmentId: binding.shipmentId,
    scenarioShipmentKey: key,
  };
  return { envelope, facts: decodeScenarioSnapshot(envelope, selection) };
}

function contextFor(snapshot: NonNullable<ReturnType<typeof decoded>['facts']>, overrides: Partial<ScenarioPresentationContext> = {}): ScenarioPresentationContext {
  return {
    authorization: 'authorized',
    viewerId: 12,
    companyId: 700,
    authContextGeneration: 'auth-generation-3',
    routeShipmentId: snapshot.selectedBinding.shipmentId,
    scenarioShipmentKey: snapshot.selectedBinding.scenarioShipmentKey,
    sessionId: snapshot.sessionId,
    epochId: snapshot.epochId,
    snapshotId: snapshot.snapshotId,
    revision: snapshot.revision,
    lifecycleState: snapshot.stage === 'WORSENED' ? 'COMPLETE' : 'ACTIVE',
    ...overrides,
  };
}

function anchor(id: number = 142): Shipment {
  const key = id === 143 ? 'B' : 'A';
  return {
    id,
    shipmentReference: `DEMO-${key}-PORTFOLIO`,
    origin: 'LIVE ORIGIN sentinel',
    destination: 'LIVE DESTINATION sentinel',
    transshipmentPort: 'LIVE PORT sentinel',
    motherVessel: 'LIVE MOTHER sentinel',
    plannedMotherArrivalAt: '2099-05-06T07:08:09Z',
    feederVessel: 'LIVE FEEDER sentinel',
    plannedFeederDepartureAt: '2099-05-07T07:08:09Z',
    createdAt: '2025-01-01T00:00:00Z',
    version: 71,
    companyId: 700,
    connectionWindow: { duration: 'LIVE WINDOW sentinel', totalSeconds: 1 },
    remainingConnectionWindow: { status: 'AVAILABLE', duration: 'LIVE REMAINING sentinel', totalSeconds: 1, missedBy: null, missedBySeconds: null },
    risk: { level: 'CRITICAL', explanation: 'LIVE RISK sentinel' },
    recommendation: {
      available: false, unavailable: 'LIVE RECOMMENDATION sentinel', response: null,
      riskLevel: 'CRITICAL', riskExplanation: 'LIVE RECOMMENDATION RISK sentinel',
      impact: { outcome: 'UNAVAILABLE', detail: 'LIVE IMPACT sentinel' },
    },
    status: 'ACTIVE',
    motherVesselPosition: {
      mmsi: '123456789', vesselName: 'LIVE MOTHER sentinel', latitude: 50, longitude: 60,
      speedOverGroundKnots: 99, courseOverGroundDegrees: 1, trueHeadingDegrees: 2,
      ingestedAt: '2099-05-06T07:08:09Z', source: 'AIS_STREAM',
    },
    feederVesselPosition: {
      mmsi: '987654321', vesselName: 'LIVE FEEDER sentinel', latitude: 51, longitude: 61,
      speedOverGroundKnots: 98, courseOverGroundDegrees: 3, trueHeadingDegrees: 4,
      ingestedAt: '2099-05-06T07:08:09Z', source: 'AIS_STREAM',
    },
    motherVesselImo: 'LIVE-IMO-MOTHER',
    motherVesselMmsi: '123456789',
    feederVesselImo: 'LIVE-IMO-FEEDER',
    feederVesselMmsi: '987654321',
    aisFeed: { status: 'LIVE', lastReceivedAt: '2099-05-06T07:08:09Z' },
    arrivalComparison: {
      estimatedArrivalAt: '2099-05-06T07:08:09Z', limitation: 'LIVE ETA sentinel', speedOverGroundKnots: 99,
      classification: 'BEHIND_PLAN', difference: 'LIVE ETA sentinel', differenceSeconds: 1, differenceNanos: 0,
      lastReceivedAt: '2099-05-06T07:08:09Z', possiblyOutdated: false, unavailableReason: null,
    },
    transshipmentArrivalDeadline: { deadline: '2099-05-08T00:00:00Z', transshipmentPort: 'LIVE PORT sentinel', applicable: true },
    delayComparison: {
      outcome: 'EXCEEDS_DEADLINE', reason: null, message: 'LIVE IMPACT sentinel',
      estimatedArrivalAt: '2099-05-06T07:08:09Z', deadline: '2099-05-08T00:00:00Z', recordedTransshipmentPort: 'LIVE PORT sentinel',
    },
  };
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('scenario presentation adapter', () => {
  it.each(['BASELINE', 'CHANGE', 'WORSENED'] as const)('maps a decoded %s snapshot as one stage', stage => {
    const { facts } = decoded(stage);
    expect(facts).toBeDefined();
    const data = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!), view());
    const expected = STAGE_FACTS[stage];

    expect(data).not.toBeNull();
    expect(data?.displayMode).toBe('scenario');
    expect(data?.scenarioReferenceCutoff).toBe(expected.cutoff);
    expect(data?.recordedAisCutoff).toBeNull();
    expect(data?.shipment.risk).toEqual(facts?.selectedShipment.risk);
    expect(data?.shipment.arrivalComparison).toEqual(facts?.selectedShipment.arrivalEstimate);
    expect(data?.shipment.delayComparison).toEqual(facts?.selectedShipment.businessImpact);
    expect(data?.roles.MOTHER.position?.latitude).toBe(stage === 'BASELINE' ? 2.26 : stage === 'CHANGE' ? 1.86 : 1.66);
    expect(data?.roles.MOTHER.position?.source).toBe('SEEDED');
    expect(data?.roles.FEEDER.position?.latitude).toBe(1.26);
    expect(data?.shipment.connectionWindow).toEqual({ duration: '1 day 6 hours', totalSeconds: 108000 });
    expect(data?.shipment.remainingConnectionWindow?.totalSeconds).toBe(expected.remaining);
  });

  it.each(['A', 'B'] as const)('selects the returned %s binding and keeps persistent and fictional identities separate', key => {
    const id = key === 'A' ? 142 : 143;
    const { facts } = decoded('CHANGE', key);
    const data = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(id), contextFor(facts!), view({ selectedRole: 'FEEDER' }));

    expect(data?.shipment.id).toBe(id);
    expect(data?.shipment.shipmentReference).toBe(`DEMO-${key}-PORTFOLIO`);
    expect(data?.shipment.shipmentReference).not.toBe(facts?.selectedShipment.scenarioShipmentReference);
    expect(data?.roles.MOTHER.sourceShipmentId).toBeNull();
    expect(data?.roles.FEEDER.sourceShipmentId).toBeNull();
    expect(data?.roles.MOTHER.identity).toEqual({ name: 'CDG279 SYNTHETIC MOTHER Api', imo: null, mmsi: '990279001' });
    expect(data?.roles.FEEDER.identity).toEqual({ name: 'CDG279 SYNTHETIC FEEDER Api', imo: null, mmsi: '990279002' });
    expect(data?.selectedRole).toBe('FEEDER');
    expect(data?.roles.MOTHER.captureId).toBeNull();
    expect(data?.roles.FEEDER.captureId).toBeNull();
    expect(data?.roles.MOTHER.mappingVersion).toBeNull();
    expect(data?.roles.FEEDER.mappingVersion).toBeNull();
    expect(data?.roles.MOTHER.history).toBeNull();
    expect(data?.roles.FEEDER.history).toBeNull();
  });

  it('does not carry A protected presentation identity or facts when B is selected', () => {
    const a = decoded('CHANGE', 'A').facts!;
    const b = decoded('CHANGE', 'B').facts!;
    const aData = adaptScenarioSnapshotToTrackPresentation(a, anchor(142), contextFor(a), view());
    const bData = adaptScenarioSnapshotToTrackPresentation(b, anchor(143), contextFor(b), view());

    expect(aData?.shipment.id).toBe(142);
    expect(aData?.shipment.delayComparison?.deadline).toBe('2026-10-01T12:00:00Z');
    expect(bData?.shipment.id).toBe(143);
    expect(bData?.shipment.shipmentReference).toBe('DEMO-B-PORTFOLIO');
    expect(bData?.shipment.delayComparison?.deadline).toBe('2026-10-02T10:00:00Z');
    expect(bData?.roles.MOTHER.sourceShipmentId).toBeNull();
    expect(bData?.roles.FEEDER.sourceShipmentId).toBeNull();
    expect(bData?.routeKey).not.toBe(aData?.routeKey);
    expect(bData?.context?.sessionId).toBe(b.sessionId);
    expect(bData?.context?.snapshotId).toBe(b.snapshotId);
  });

  it('keeps actual synthetic names, MMSIs, nullable readings, timestamps, and seeded provenance', () => {
    const { facts } = decoded('BASELINE', 'A', {
      motherPosition: { courseOverGroundDegrees: null, trueHeadingDegrees: null },
      feederPosition: { speedOverGroundKnots: null, courseOverGroundDegrees: null, trueHeadingDegrees: null },
    });
    expect(facts).toBeDefined();
    const data = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!), view());

    expect(facts?.aisObservations[0].receivedAtUtc).toBe('2026-10-01T00:00:00Z');
    expect(facts?.aisObservations[0].position.ingestedAt).toBe('2026-10-01T00:00:00Z');
    expect(facts?.mapPlaybackCutoffUtc).toBe('2026-10-01T00:00:00Z');
    expect(data?.roles.MOTHER.position).toMatchObject({
      mmsi: '990279001', vesselName: 'CDG279 SYNTHETIC MOTHER Api',
      speedOverGroundKnots: 10, courseOverGroundDegrees: null, trueHeadingDegrees: null,
      ingestedAt: '2026-10-01T00:00:00Z', source: 'SEEDED',
    });
    expect(data?.roles.FEEDER.position).toMatchObject({
      mmsi: '990279002', vesselName: 'CDG279 SYNTHETIC FEEDER Api',
      speedOverGroundKnots: null, courseOverGroundDegrees: null, trueHeadingDegrees: null,
      ingestedAt: '2026-10-01T00:00:00Z', source: 'SEEDED',
    });
    expect(data?.recordedAisCutoff).toBeNull();
    expect(data?.roles.MOTHER.history).toBeNull();
    expect(data?.roles.FEEDER.history).toBeNull();
  });

  it('retains an authorized role when the other role has no usable position', () => {
    const { facts } = decoded('CHANGE', 'A', {
      feederPosition: { latitude: null, longitude: null, positionValid: false, speedOverGroundKnots: null },
    });
    expect(facts).toBeDefined();
    expect(facts?.aisObservations.find(row => row.vesselRole === 'FEEDER')?.usablePosition).toBeNull();
    const data = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!), view());

    expect(data?.roles.MOTHER.state).toBe('available');
    expect(data?.roles.MOTHER.position).not.toBeNull();
    expect(data?.roles.FEEDER.state).toBe('unavailable');
    expect(data?.roles.FEEDER.position).toBeNull();
    expect(data?.roles.FEEDER.identity.mmsi).toBe('990279002');
  });

  it('rejects invalid snapshot coordinates without returning a partial presentation', () => {
    const { envelope, facts } = decoded('BASELINE', 'A', { motherPosition: { latitude: 91 } });
    expect(facts).toBeUndefined();
    const previous = decoded('CHANGE').facts!;
    expect(adaptScenarioSnapshotToTrackPresentation(null, anchor(), contextFor(previous), view())).toBeNull();
    expect(envelope.snapshot?.aisObservations[0].position.latitude).toBe(91);
  });

  it('uses scenario port/schedule and never copies operational anchor route or calculations', () => {
    const { facts } = decoded('CHANGE');
    const authorizedAnchor = anchor();
    const data = adaptScenarioSnapshotToTrackPresentation(facts!, authorizedAnchor, contextFor(facts!), view());

    expect(data?.shipment.origin).toBe('Not recorded');
    expect(data?.shipment.destination).toBe('Not recorded');
    expect(data?.shipment.transshipmentPort).toBe('Synthetic Multi-source Port');
    expect(data?.shipment.plannedMotherArrivalAt).toBe('2026-10-01T06:00:00Z');
    expect(data?.shipment.plannedFeederDepartureAt).toBe('2026-10-02T12:00:00Z');
    expect(data?.shipment.risk).toEqual(facts?.selectedShipment.risk);
    expect(data?.shipment.risk?.explanation).not.toContain('LIVE');
    expect(data?.shipment.arrivalComparison?.estimatedArrivalAt).toBe('2026-10-01T16:00:00Z');
    expect(data?.shipment.delayComparison?.deadline).toBe('2026-10-01T12:00:00Z');
    expect(data?.shipment.recommendation).toBeUndefined();
    expect(data?.shipment.transshipmentArrivalDeadline).toBeUndefined();
    expect(data?.shipment.motherVesselPosition).toBeNull();
    expect(data?.shipment.feederVesselPosition).toBeNull();
    expect(data?.shipment.aisFeed).toBeNull();
    expect(data?.shipment.motherVesselMmsi).toBe('990279001');
    expect(data?.shipment.motherVesselImo).toBeNull();
    expect(data?.shipment.feederVesselMmsi).toBe('990279002');
    expect(data?.shipment.feederVesselImo).toBeNull();
  });

  it('keeps route identity stable across stages, revision, refresh, role selection, and auth-view refresh', () => {
    const baseline = decoded('BASELINE').facts!;
    const change = decoded('CHANGE').facts!;
    const worsened = decoded('WORSENED').facts!;
    const a = adaptScenarioSnapshotToTrackPresentation(baseline, anchor(), contextFor(baseline), view());
    const b = adaptScenarioSnapshotToTrackPresentation(change, anchor(), contextFor(change), view({ selectedRole: 'FEEDER' }));
    const c = adaptScenarioSnapshotToTrackPresentation(worsened, anchor(), contextFor(worsened), view());
    const refresh = adaptScenarioSnapshotToTrackPresentation(change, anchor(), contextFor(change), view());

    expect(a?.routeKey).toBe(b?.routeKey);
    expect(b?.routeKey).toBe(c?.routeKey);
    expect(b?.routeKey).toBe(refresh?.routeKey);
    expect(b?.context?.revision).toBe('2');
    expect(b?.context?.snapshotId).toBe(change.snapshotId);
    expect(c?.context?.revision).toBe('3');
    expect(c?.context?.snapshotId).toBe(worsened.snapshotId);
    expect(c?.scenarioReferenceCutoff).toBe(worsened.simulationCutoffUtc);
    expect(c?.recordedAisCutoff).toBeNull();
  });

  it('changes accepted authorization context by viewer, company, auth generation, epoch, and revision', () => {
    const { facts } = decoded('CHANGE');
    const original = contextFor(facts!);
    const base = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), original, view())!;
    const otherViewer = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!, { viewerId: 13 }), view())!;
    const otherCompany = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!, { companyId: 701 }), view())!;
    const otherGeneration = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!, { authContextGeneration: 'auth-generation-4' }), view())!;
    const newEpochFacts = decoded('CHANGE', 'A', {
      epochId: '84dab1c6-1d4b-4889-8dde-0b31986fed29', revision: 1,
      snapshotId: '363052b2-fca1-4116-b559-f387ac6c2dc3',
    }).facts!;
    const newEpoch = adaptScenarioSnapshotToTrackPresentation(newEpochFacts, anchor(), contextFor(newEpochFacts), view())!;
    const newRevisionFacts = decoded('CHANGE', 'A', {
      revision: 4, snapshotId: '463052b2-fca1-4116-b559-f387ac6c2dc4',
    }).facts!;
    const newRevision = adaptScenarioSnapshotToTrackPresentation(newRevisionFacts, anchor(), contextFor(newRevisionFacts), view())!;

    expect(otherViewer.context?.scopeId).not.toBe(base.context?.scopeId);
    expect(otherCompany.context?.scopeId).not.toBe(base.context?.scopeId);
    expect(otherGeneration.context?.scopeId).not.toBe(base.context?.scopeId);
    expect(newEpoch.context?.epoch).toBe(newEpochFacts.epochId);
    expect(newEpoch.context?.snapshotId).toBe(newEpochFacts.snapshotId);
    expect(newEpoch.context?.revision).toBe('1');
    expect(newRevision.context?.epoch).toBe(base.context?.epoch);
    expect(newRevision.context?.snapshotId).toBe(newRevisionFacts.snapshotId);
    expect(newRevision.context?.revision).toBe('4');

    const wrongEpoch = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!, { epochId: '84dab1c6-1d4b-4889-8dde-0b31986fed29' }), view());
    const wrongRevision = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!, { revision: 3 }), view());
    const wrongSnapshot = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!, { snapshotId: SNAPSHOT_IDS[2] }), view());
    expect(wrongEpoch).toBeNull();
    expect(wrongRevision).toBeNull();
    expect(wrongSnapshot).toBeNull();
  });

  it('rejects route, anchor, and A/B binding mismatches', () => {
    const a = decoded('CHANGE', 'A').facts!;
    const b = decoded('CHANGE', 'B').facts!;
    expect(adaptScenarioSnapshotToTrackPresentation(a, anchor(143), contextFor(a), view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(a, anchor(142), contextFor(a, { routeShipmentId: 143 }), view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(a, anchor(142), contextFor(a, { scenarioShipmentKey: 'B' }), view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(b, anchor(142), contextFor(b), view())).toBeNull();
  });

  it('returns no active data after Reset, UNSTARTED, missing snapshot, or global authorization denial', () => {
    const old = decoded('WORSENED').facts!;
    const resetContext: ScenarioPresentationContext = contextFor(old, {
      epochId: '84dab1c6-1d4b-4889-8dde-0b31986fed29',
      snapshotId: null,
      revision: 0,
      lifecycleState: 'UNSTARTED',
    });
    const unstartedContext: ScenarioPresentationContext = contextFor(old, {
      snapshotId: null, revision: 0, lifecycleState: 'UNSTARTED',
    });
    const deniedContext: ScenarioPresentationContext = contextFor(old, { authorization: 'denied' });

    expect(adaptScenarioSnapshotToTrackPresentation(null, anchor(), resetContext, view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(old, anchor(), resetContext, view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(null, anchor(), unstartedContext, view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(old, anchor(), deniedContext, view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(old, null, contextFor(old), view())).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(old, anchor(), null, view())).toBeNull();
  });

  it('does not honor unsupported recorded/live modes or history selection', () => {
    const { facts } = decoded('CHANGE');
    const accepted = contextFor(facts!);
    expect(adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), accepted, view({ displayMode: 'recorded' }))).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), accepted, view({ displayMode: 'live' }))).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), accepted, view({ window: 6 }))).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), accepted, view({ selectedPointId: 123 }))).toBeNull();
    expect(adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), accepted, view({ recordedAisCutoff: '2026-10-01T04:00:00Z' }))).toBeNull();
  });

  it('never mutates decoded facts or the authorized anchor and returns a frozen display projection', () => {
    const { envelope, facts } = decoded('CHANGE');
    const factsBefore = structuredClone(facts);
    const authorizedAnchor = anchor();
    const anchorBefore = structuredClone(authorizedAnchor);
    const data = adaptScenarioSnapshotToTrackPresentation(facts!, authorizedAnchor, contextFor(facts!), view());

    expect(envelope).toBeDefined();
    expect(facts).toEqual(factsBefore);
    expect(authorizedAnchor).toEqual(anchorBefore);
    expect(data?.shipment).not.toBe(authorizedAnchor);
    expect(data && Object.isFrozen(data)).toBe(true);
    expect(data && Object.isFrozen(data.shipment)).toBe(true);
    expect(data?.shipment.version).toBe(authorizedAnchor.version);
    expect(data?.shipment.id).toBe(authorizedAnchor.id);
  });

  it('is independent of browser time and performs no API calls or timer scheduling', () => {
    const { facts } = decoded('CHANGE');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-01T00:00:00Z'));
    const first = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!), view());
    const firstTimerCount = vi.getTimerCount();
    vi.setSystemTime(new Date('2040-12-31T23:59:59Z'));
    const second = adaptScenarioSnapshotToTrackPresentation(facts!, anchor(), contextFor(facts!), view());

    expect(second).toEqual(first);
    expect(firstTimerCount).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('changes route identity for a real displayed geometry or authorized-anchor change', () => {
    const syntheticPort = decoded('CHANGE', 'A', { transshipmentPort: 'Synthetic Multi-source Port' }).facts!;
    const locatedPort = decoded('CHANGE', 'A', { transshipmentPort: 'Singapore' }).facts!;
    const syntheticRoute = adaptScenarioSnapshotToTrackPresentation(syntheticPort, anchor(142), contextFor(syntheticPort), view());
    const locatedRoute = adaptScenarioSnapshotToTrackPresentation(locatedPort, anchor(142), contextFor(locatedPort), view());
    const bSnapshot = decoded('CHANGE', 'B', { transshipmentPort: 'Synthetic Multi-source Port' }).facts!;
    const otherAnchor = adaptScenarioSnapshotToTrackPresentation(bSnapshot, anchor(143), contextFor(bSnapshot), view());

    expect(syntheticRoute?.routeKey).not.toBe(locatedRoute?.routeKey);
    expect(otherAnchor?.routeKey).not.toBe(syntheticRoute?.routeKey);
  });
});
