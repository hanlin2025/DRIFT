import type {
  ScenarioSessionEnvelope, ScenarioShipmentBinding, ScenarioShipmentKey, ScenarioShipmentRead, ScenarioStage,
} from './scenarioApi';

export const SESSION_ID = 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf';
export const EPOCH_ID = '74dab1c6-1d4b-4889-8dde-0b31986fed28';
export const RESET_EPOCH_ID = '4618e3c7-2006-4901-a211-01fbbaf8186c';
export const BINDINGS: readonly ScenarioShipmentBinding[] = [
  { scenarioShipmentKey: 'A', shipmentId: 142, shipmentReference: 'DEMO-A-PORTFOLIO', scenarioShipmentReference: 'DEMO-CDG279-Api-A' },
  { scenarioShipmentKey: 'B', shipmentId: 143, shipmentReference: 'DEMO-B-PORTFOLIO', scenarioShipmentReference: 'DEMO-CDG279-Api-B' },
];

const STAGES = {
  BASELINE: {
    order: 0, fixture: 'baseline', cutoff: '2026-10-01T00:00:00Z', speed: 10, latitude: 2.26,
    arrival: '2026-10-01T06:00:00Z', classification: 'ON_SCHEDULE', difference: 'On schedule · estimated at the planned time', differenceSeconds: 0,
    remaining: 108000, risk: 'LOW',
    riskExplanation: "At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
  },
  CHANGE: {
    order: 1, fixture: 'material-slowdown', cutoff: '2026-10-01T04:00:00Z', speed: 3, latitude: 1.86,
    arrival: '2026-10-01T16:00:00Z', classification: 'BEHIND_PLAN', difference: 'Behind plan · estimated 10h later than planned', differenceSeconds: 36000,
    remaining: 72000, risk: 'MEDIUM',
    riskExplanation: "Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
  },
  WORSENED: {
    order: 2, fixture: 'worsened-connection', cutoff: '2026-10-01T08:00:00Z', speed: 1, latitude: 1.66,
    arrival: '2026-10-02T08:00:00Z', classification: 'BEHIND_PLAN', difference: 'Behind plan · estimated 26h later than planned', differenceSeconds: 93600,
    remaining: 14400, risk: 'HIGH',
    riskExplanation: "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
  },
} as const;

function shipmentRead(key: ScenarioShipmentKey, stage: ScenarioStage): ScenarioShipmentRead {
  const row = STAGES[stage];
  const binding = BINDINGS.find(item => item.scenarioShipmentKey === key);
  if (!binding) throw new Error('Missing fixture binding.');
  const deadline = key === 'A' ? '2026-10-01T12:00:00Z' : '2026-10-02T10:00:00Z';
  const exceedsDeadline = key === 'A' && stage !== 'BASELINE';
  return {
    scenarioShipmentKey: key,
    sourceShipmentId: binding.shipmentId,
    scenarioShipmentReference: binding.scenarioShipmentReference,
    transshipmentPort: 'Synthetic Multi-source Port',
    plannedMotherArrivalAt: '2026-10-01T06:00:00Z',
    plannedFeederDepartureAt: '2026-10-02T12:00:00Z',
    plannedConnectionWindow: { status: 'AVAILABLE', window: { duration: '1 day 6 hours', totalSeconds: 108000 } },
    remainingConnectionWindow: {
      status: 'AVAILABLE', duration: row.remaining === 108000 ? '1 day 6 hours' : row.remaining === 72000 ? '20 hours' : '4 hours',
      totalSeconds: row.remaining, missedBy: null, missedBySeconds: null,
    },
    arrivalEstimate: {
      estimatedArrivalAt: row.arrival,
      limitation: 'Approximate arrival — based on straight-line distance and current speed.',
      speedOverGroundKnots: row.speed,
      classification: row.classification,
      difference: row.difference,
      differenceSeconds: row.differenceSeconds,
      differenceNanos: 0,
      lastReceivedAt: row.cutoff,
      possiblyOutdated: false,
      unavailableReason: null,
    },
    risk: { level: row.risk, explanation: row.riskExplanation },
    businessImpact: {
      outcome: exceedsDeadline ? 'EXCEEDS_DEADLINE' : 'NO_PREDICTED_IMPACT',
      reason: null,
      message: exceedsDeadline ? 'The predicted arrival exceeds the recorded mother-vessel transshipment-arrival deadline.'
        : 'No predicted impact on the recorded mother-vessel transshipment-arrival deadline.',
      estimatedArrivalAt: row.arrival,
      deadline,
      recordedTransshipmentPort: 'Synthetic Multi-source Port',
    },
  };
}

export function scenarioEnvelope(stage: ScenarioStage | null, epochId = EPOCH_ID): ScenarioSessionEnvelope {
  const row = stage ? STAGES[stage] : null;
  const revision = row ? row.order + 1 : 0;
  const base = {
    apiVersion: '1' as const,
    sessionId: SESSION_ID,
    scenarioId: 'CDG279-Api',
    scenarioContractVersion: 'cdg279.synthetic-disruption.v1' as const,
    epochId,
    revision,
    capabilities: {
      canRead: true,
      canStart: stage === null,
      canAdvance: stage !== null && stage !== 'WORSENED',
      canReset: stage !== null,
      canGenerateAssessment: false as const,
    },
    shipments: BINDINGS,
  };
  if (!stage || !row) return { ...base, state: 'UNSTARTED', snapshot: null };

  const motherPosition = {
    mmsi: '990279001', vesselName: 'CDG279 SYNTHETIC MOTHER', latitude: row.latitude, longitude: 103.82,
    speedOverGroundKnots: row.speed, courseOverGroundDegrees: 180,
    trueHeadingDegrees: stage === 'BASELINE' ? 180 : null, navigationalStatus: 0,
    positionValid: true, aisUtcSecond: 0, ingestedAt: row.cutoff, source: 'SEEDED',
  };
  const feederPosition = {
    mmsi: '990279002', vesselName: 'CDG279 SYNTHETIC FEEDER', latitude: 1.26, longitude: 103.82,
    speedOverGroundKnots: 0, courseOverGroundDegrees: null, trueHeadingDegrees: null,
    navigationalStatus: 5, positionValid: true, aisUtcSecond: 0, ingestedAt: row.cutoff, source: 'SEEDED',
  };
  const context = {
    contractVersion: 'cdg246.maritime-evidence-bundle.v1',
    enabled: true,
    sourceMode: 'UNAVAILABLE',
    assessmentCutoffUtc: row.cutoff,
    shipmentAssociationConfidence: 'UNAVAILABLE',
    limitation: 'Read-only synthetic demonstration facts. Human review is required.',
    evidence: [], notices: [], aiExplanation: null, suggestedActions: null,
  };
  return {
    ...base,
    state: stage === 'WORSENED' ? 'COMPLETE' : 'ACTIVE',
    snapshot: {
      snapshotId: `063052b2-fca1-4116-b559-f387ac6c2dc${row.order}`,
      epochId,
      revision,
      stage,
      stageOrder: row.order as 0 | 1 | 2,
      stageCount: 3,
      fixtureStageId: row.fixture,
      simulationCutoffUtc: row.cutoff,
      mapPlaybackCutoffUtc: row.cutoff,
      shipments: BINDINGS.map(binding => shipmentRead(binding.scenarioShipmentKey, stage)),
      aisObservations: [
        { vesselRole: 'MOTHER' as const, observedAtUtc: row.cutoff, receivedAtUtc: row.cutoff,
          sourceMode: 'SYNTHETIC_DEMONSTRATION', position: motherPosition },
        { vesselRole: 'FEEDER' as const, observedAtUtc: row.cutoff, receivedAtUtc: row.cutoff,
          sourceMode: 'SYNTHETIC_DEMONSTRATION', position: feederPosition },
      ],
      maritimeContext: context,
    },
  };
}
