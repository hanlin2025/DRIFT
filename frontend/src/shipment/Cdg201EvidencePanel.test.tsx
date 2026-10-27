import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Cdg201EvidencePanel } from './Cdg201EvidencePanel';
import type { Cdg201ArrivalEvidence } from './api';

const evidence: Cdg201ArrivalEvidence = {
  contractVersion: 'cdg201.drift-arrival-evidence.v1', evidenceState: 'RECORDED_EVIDENCE_REPLAY',
  vesselName: 'KOTA LARIS', vesselImo: '9351048', previousExpectedArrival: '2026-10-28 23:00:00',
  revisedExpectedArrival: '2026-10-29 02:00:00', revisionMinutes: 180,
  previousCollectionUtc: '2026-10-26T15:10:22.248554Z', latestCollectionUtc: '2026-10-26T15:46:03.213897Z',
  previousCollectionRunId: 'run-one', latestCollectionRunId: 'run-two', sourceManifest: 'evidence.manifest.json',
  previousSourceManifest: 'prior-evidence.manifest.json', previousSourceRawFile: 'prior-evidence.json', sourceRawFile: 'evidence.json', confidence: 'conservative_context_time_continuity', ambiguity: 'NONE_FOR_REPLAY_EVENT',
  sourceTimestampSemantics: 'Source timezone is undocumented.', locationFrom: 'SEA', locationTo: 'PEBGA',
  locationMappingStatus: 'UNKNOWN_MPA_CODE_NOT_MAPPED_TO_DRIFT_PORT', shipmentAssociation: 'UNVERIFIED_IMO_ONLY',
  limitations: 'Not a verified shipment.',
  simulatedScenario: { state: 'SIMULATED_CONNECTION_ASSUMPTIONS', originalConnectionMarginMinutes: 780,
    revisedConnectionMarginMinutes: 600, reductionMinutes: 180, simulatedRiskBefore: 'MEDIUM', simulatedRiskAfter: 'HIGH',
    disclaimer: 'Not a production risk result.' },
};

describe('Cdg201EvidencePanel', () => {
  it('shows recorded evidence and simulated impact without claiming a real connection', () => {
    render(<Cdg201EvidencePanel evidence={evidence} />);
    expect(screen.getByRole('article', { name: 'CDG-201 external arrival evidence' })).toHaveTextContent('KOTA LARIS · IMO 9351048');
    expect(screen.getByText('+180 minutes')).toBeInTheDocument();
    expect(screen.getByText(/unverified imo only/i)).toBeInTheDocument();
    expect(screen.getByText(/Margin: 780 → 600 minutes/)).toBeInTheDocument();
    expect(screen.getByText(/Not the production shipment risk/)).toBeInTheDocument();
  });

  it('renders nothing where no replay evidence is available', () => {
    const { container } = render(<Cdg201EvidencePanel evidence={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
