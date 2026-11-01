import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MaritimeContextPanel } from './MaritimeContextPanel';
import type { MaritimeContext } from './api';

const context: MaritimeContext = {
  contractVersion: 'cdg246.maritime-evidence-bundle.v1', enabled: true, sourceMode: 'SYNTHETIC_DEMONSTRATION',
  assessmentCutoffUtc: '2026-09-30T22:45:00Z', shipmentAssociationConfidence: 'VERIFIED', limitation: 'Fictional scenario.',
  notices: [], aiExplanation: null, suggestedActions: null,
  evidence: [
    { sourceType: 'MPA_ETA_REVISION', sourceMode: 'SYNTHETIC_DEMONSTRATION', evidenceId: 'synthetic-mpa', title: 'Synthetic revision', sourceIdentifier: 'SYNTHETIC-MPA', sourceUrl: '', citation: 'Fictional.', sourceEventAt: null, observedAt: null, retrievedAtUtc: '2026-09-30T21:00:00Z', freshness: 'FRESH', previousValue: '2026-10-01T05:00:00+08:00', currentValue: '2026-10-01T06:00:00+08:00', changeMagnitude: '+60 minutes', activityCompatibility: 'COMPATIBLE', locationCompatibility: 'COMPATIBLE', timeCompatibility: 'COMPATIBLE', compatibilityState: 'COMPATIBLE', evidenceSufficient: true, humanReviewRequired: true, shipmentAssociationConfidence: 'VERIFIED', limitation: 'Fictional fixture only.' },
    { sourceType: 'NEA_WEATHER', sourceMode: 'UNAVAILABLE', evidenceId: 'live-nea-unavailable', title: 'Live NEA weather context not ingested', sourceIdentifier: 'NEA', sourceUrl: '', citation: 'No live snapshot.', sourceEventAt: null, observedAt: null, retrievedAtUtc: null, freshness: 'UNAVAILABLE', previousValue: '', currentValue: '', changeMagnitude: '', activityCompatibility: 'UNKNOWN', locationCompatibility: 'UNKNOWN', timeCompatibility: 'UNKNOWN', compatibilityState: 'UNKNOWN', evidenceSufficient: false, humanReviewRequired: true, shipmentAssociationConfidence: 'UNAVAILABLE', limitation: 'Separate from synthetic weather.' },
    { sourceType: 'MPA_OFFICIAL_NOTICE', sourceMode: 'SYNTHETIC_DEMONSTRATION', evidenceId: 'synthetic-incompatible', title: 'Different fictional area', sourceIdentifier: 'SYNTHETIC-PMN', sourceUrl: '', citation: 'Fictional.', sourceEventAt: null, observedAt: null, retrievedAtUtc: '2026-09-30T22:30:00Z', freshness: 'FRESH', previousValue: '', currentValue: '', changeMagnitude: '', activityCompatibility: 'INCOMPATIBLE', locationCompatibility: 'INCOMPATIBLE', timeCompatibility: 'COMPATIBLE', compatibilityState: 'INCOMPATIBLE', evidenceSufficient: true, humanReviewRequired: true, shipmentAssociationConfidence: 'VERIFIED', limitation: 'Fictional fixture only.' },
  ],
};

describe('MaritimeContextPanel', () => {
  it('keeps operational information prominent while retaining source qualification in evidence details', () => {
    render(<MaritimeContextPanel context={context} unavailable={false} />);
    expect(screen.getByText('What changed')).toBeInTheDocument();
    expect(screen.getAllByText('Why it matters').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Uncertainty').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Evidence details')).toHaveLength(3);
    expect(screen.getAllByText(/fictional scenario evidence/i)).toHaveLength(2);
    expect(screen.getByText(/source-availability status/)).toBeInTheDocument();
    expect(screen.getByText(/excluded from the AI assessment/)).toBeInTheDocument();
  });
});
