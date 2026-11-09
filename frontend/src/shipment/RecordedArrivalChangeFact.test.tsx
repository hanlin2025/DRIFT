import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Cdg201ArrivalEvidence, Shipment } from './api';
import { RecordedArrivalChangeFact } from './RecordedArrivalChangeFact';

const evidence: Cdg201ArrivalEvidence = {
  contractVersion: 'cdg201.drift-arrival-evidence.v1', evidenceState: 'RECORDED_EVIDENCE_REPLAY',
  vesselName: 'KOTA LARIS', vesselImo: '9351048', previousExpectedArrival: '2026-10-28 23:00:00',
  revisedExpectedArrival: '2026-10-29 02:00:00', previousExpectedArrivalSgt: '2026-10-28T23:00:00+08:00',
  previousExpectedArrivalUtc: '2026-10-28T15:00:00+00:00', revisedExpectedArrivalSgt: '2026-10-29T02:00:00+08:00',
  revisedExpectedArrivalUtc: '2026-10-28T18:00:00+00:00', revisionMinutes: 180,
  previousCollectionUtc: '2026-10-26T15:10:22.248554Z', latestCollectionUtc: '2026-10-26T15:46:03.213897Z',
  previousCollectionRunId: 'run-one', latestCollectionRunId: 'run-two', sourceManifest: 'evidence.manifest.json',
  previousSourceManifest: 'prior-evidence.manifest.json', previousSourceRawFile: 'prior-evidence.json', sourceRawFile: 'evidence.json',
  confidence: 'conservative_context_time_continuity', ambiguity: 'NONE_FOR_REPLAY_EVENT',
  sourceTimestampSemantics: 'Source timezone is Asia/Singapore.', locationFrom: 'SEA', locationTo: 'PEBGA',
  locationMappingStatus: 'UNKNOWN_MPA_CODE_NOT_MAPPED_TO_DRIFT_PORT', locationReferences: [],
  shipmentAssociation: 'UNVERIFIED_IMO_ONLY', limitations: 'Not a verified shipment.',
  simulatedScenario: {
    state: 'SIMULATED_CONNECTION_ASSUMPTIONS', originalConnectionMarginMinutes: 780,
    revisedConnectionMarginMinutes: 600, reductionMinutes: 180, simulatedRiskBefore: 'MEDIUM', simulatedRiskAfter: 'HIGH',
    disclaimer: 'Not a production risk result.',
  },
};

const shipment = {
  risk: { level: 'LOW', explanation: 'At least 24 hours to transfer cargo between vessels.' },
} as Shipment;

function reading(current: Shipment, replay: Cdg201ArrivalEvidence | null = evidence) {
  return render(<dl><RecordedArrivalChangeFact shipment={current} evidence={replay} /></dl>);
}

describe('RecordedArrivalChangeFact', () => {
  it('names the recorded revision and the connection risk already on the shipment', () => {
    reading(shipment);
    expect(screen.getByText(/KOTA LARIS, IMO 9351048, has a recorded expected-arrival revision from 2026-10-28 23:00:00 to 2026-10-29 02:00:00, 180 minutes later/)).toBeInTheDocument();
    expect(screen.getByText(/This recorded revision is not an actual arrival/)).toBeInTheDocument();
    expect(screen.getByText(/The connection risk to act on is the one already on this shipment: Low. At least 24 hours to transfer cargo between vessels\./)).toBeInTheDocument();
    expect(screen.queryByText(/780/)).not.toBeInTheDocument();
    expect(screen.queryByText(/MEDIUM/)).not.toBeInTheDocument();
    expect(screen.queryByText(/booking/i)).not.toBeInTheDocument();
  });

  it('says the connection risk is not assessed without using the simulated classification', () => {
    reading({ ...shipment, risk: null });
    expect(screen.getByText('The connection risk on this shipment is not assessed.')).toBeInTheDocument();
    expect(screen.queryByText(/HIGH/)).not.toBeInTheDocument();
    expect(screen.queryByText(/simulated/i)).not.toBeInTheDocument();
  });

  it('says a negative revision is earlier and does not hardcode 180', () => {
    reading(shipment, { ...evidence, revisionMinutes: -45 });
    expect(screen.getByText(/45 minutes earlier/)).toBeInTheDocument();
    expect(screen.queryByText(/180 minutes/)).not.toBeInTheDocument();
  });

  it('renders nothing when there is no replay', () => {
    const { container } = reading(shipment, null);
    expect(container).toHaveTextContent('');
    expect(screen.queryByText('Recorded arrival change')).not.toBeInTheDocument();
  });
});
