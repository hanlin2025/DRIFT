import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { type MaritimeContext, type MaritimeEvidence } from './api';
import { MaritimeContextPanel } from './MaritimeContextPanel';

function evidence(overrides: Partial<MaritimeEvidence> = {}): MaritimeEvidence {
  return {
    sourceType: 'MPA_ETA_REVISION', sourceMode: 'RECORDED_REPLAY', evidenceId: 'eta-1', title: 'Recorded MPA expected-arrival revision',
    sourceIdentifier: 'MPA OCEANS-X Vessels Due to Arrive', sourceUrl: '', citation: 'Manifest citation',
    sourceEventAt: '2026-10-29T02:00:00+08:00', observedAt: '2026-10-26T15:46:03Z', retrievedAtUtc: '2026-10-26T15:46:03Z',
    freshness: 'RECORDED_REPLAY', previousValue: '2026-10-28 23:00:00', currentValue: '2026-10-29 02:00:00', changeMagnitude: '+180 minutes',
    activityCompatibility: 'UNKNOWN', locationCompatibility: 'UNKNOWN', timeCompatibility: 'UNKNOWN', compatibilityState: 'UNKNOWN',
    evidenceSufficient: true, humanReviewRequired: true, shipmentAssociationConfidence: 'UNVERIFIED_IMO_ONLY',
    limitation: 'Recorded replay only.',
    ...overrides,
  };
}

function context(items: MaritimeEvidence[], overrides: Partial<MaritimeContext> = {}): MaritimeContext {
  return {
    contractVersion: 'cdg246.maritime-evidence-bundle.v1', enabled: true, sourceMode: 'RECORDED_REPLAY',
    assessmentCutoffUtc: '2026-10-28T00:00:00Z', shipmentAssociationConfidence: 'UNVERIFIED_IMO_ONLY',
    limitation: 'Human review is required. This bundle does not change ETA, shipment, risk, alerts, or operational decisions.',
    evidence: items, notices: [], aiExplanation: null, suggestedActions: null,
    ...overrides,
  };
}

function showsNoSuccessfulAssessment() {
  const region = screen.getByRole('region', { name: 'Maritime Context' });
  expect(region).toHaveTextContent('Evidence review');
  expect(region).toHaveTextContent('This panel lists context records and quality signals; it does not report model-input eligibility or AI citations.');
  expect(region).not.toHaveTextContent('Validated result');
  expect(region).not.toHaveTextContent('Generation timeNot recorded');
  expect(region.textContent).not.toMatch(/\b(GENERATED|VALID)\b/);
  expect(region).toHaveTextContent('This does not change the shipment, ETA, risk, alerts, recommendation, or decision.');
}

describe('MaritimeContextPanel', () => {
  it('shows a denied reassessment when maritime context is off', () => {
    render(<MaritimeContextPanel context={context([], { enabled: false, sourceMode: 'DISABLED' })} unavailable={false} />);
    expect(screen.getByText('Maritime context is not enabled for this environment.')).toBeInTheDocument();
    expect(screen.getByText(/Maritime reassessment is off/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Maritime Context' }).textContent).not.toMatch(/\b(GENERATED|VALID)\b/);
  });

  it('shows updated evidence with operational context and preserves provenance and uncertainty in details', async () => {
    const user = userEvent.setup();
    render(<MaritimeContextPanel context={context([
      evidence(),
      evidence({
        sourceType: 'NEA_WEATHER', sourceMode: 'UNAVAILABLE', evidenceId: 'weather-none', title: 'NEA weather context unavailable',
        freshness: 'UNAVAILABLE', previousValue: '', currentValue: '', changeMagnitude: '', evidenceSufficient: false,
        shipmentAssociationConfidence: 'UNAVAILABLE', limitation: 'No weather causation is claimed.',
      }),
    ])} unavailable={false} />);
    showsNoSuccessfulAssessment();
    expect(screen.getByText('Updated evidence')).toBeInTheDocument();
    expect(screen.getByText('Evidence available to review (2)')).toBeInTheDocument();
    expect(screen.getByRole('time')).toHaveAttribute('dateTime', '2026-10-28T00:00:00Z');
    expect(screen.getByText('Arrival schedule revision · recorded replay')).toBeInTheDocument();
    expect(screen.getByText((_, node) => node?.textContent?.replace(/\s+/g, ' ').trim() === 'Compatibility: unknown · Freshness: recorded replay · Evidence signal: sufficient')).toBeInTheDocument();
    expect(screen.getByText('Change:')).toBeInTheDocument();
    expect(screen.getAllByText(/Planning relevance:/).length).toBeGreaterThan(0);
    const details = screen.getAllByText('Full record, provenance, timestamps and citations');
    await user.click(details[0]);
    expect(screen.getByRole('heading', { name: 'Recorded MPA expected-arrival revision' })).toBeInTheDocument();
    expect(screen.getAllByText(/An IMO match is not a verified visit/).length).toBeGreaterThan(0);
    await user.click(details[1]);
    expect(screen.getByText(/This source failed\. It is not a validated result\./)).toBeInTheDocument();
    expect(screen.getByText(/Regional weather is not a demonstrated cause for this shipment/)).toBeInTheDocument();
    expect(screen.getAllByText(/\+180 minutes/)).toHaveLength(2);
  });

  it('shows pending review when evidence needs a person and has no recorded change', async () => {
    const user = userEvent.setup();
    render(<MaritimeContextPanel context={context([evidence({
      sourceType: 'MPA_OFFICIAL_NOTICE', sourceMode: 'SYNTHETIC_DEMONSTRATION', evidenceId: 'notice-1',
      title: 'Demonstration maritime operational notice', freshness: 'SYNTHETIC_DEMONSTRATION',
      previousValue: '', currentValue: '', changeMagnitude: '', evidenceSufficient: false,
      sourceUrl: 'https://www.mpa.gov.sg/',
    })])} unavailable={false} />);
    expect(screen.getByText('Pending review')).toBeInTheDocument();
    showsNoSuccessfulAssessment();
    await user.click(screen.getByText('Full record, provenance, timestamps and citations'));
    expect(screen.getByText('A general notice is not a specific berth event.')).toBeInTheDocument();
    expect(screen.getByText(/Fictional demo evidence; it is not an authentic maritime observation/)).toBeInTheDocument();
  });

  it('shows stale recorded evidence when nothing live or changed is present', () => {
    render(<MaritimeContextPanel context={context([evidence({
      humanReviewRequired: false, previousValue: '', currentValue: '', changeMagnitude: '',
    })])} unavailable={false} />);
    expect(screen.getByText('Stale recorded evidence')).toBeInTheDocument();
    showsNoSuccessfulAssessment();
  });

  it('shows a source failure without displaying a successful assessment', () => {
    render(<MaritimeContextPanel context={context([evidence({
      sourceType: 'NEA_WEATHER', sourceMode: 'UNAVAILABLE', evidenceId: 'weather-none', title: 'NEA weather context unavailable',
      freshness: 'UNAVAILABLE', previousValue: '', currentValue: '', changeMagnitude: '', evidenceSufficient: false,
      shipmentAssociationConfidence: 'UNAVAILABLE',
    })])} unavailable={false} />);
    expect(screen.getByText('Source failed')).toBeInTheDocument();
    showsNoSuccessfulAssessment();
  });

  it('shows live evidence as available to review and still records no validated result', () => {
    render(<MaritimeContextPanel context={context([evidence({
      sourceMode: 'LIVE', freshness: 'APPLICABLE', humanReviewRequired: false, changeMagnitude: '',
      previousValue: '', currentValue: '', shipmentAssociationConfidence: 'UNKNOWN',
    })])} unavailable={false} />);
    expect(screen.getByText('Evidence available')).toBeInTheDocument();
    showsNoSuccessfulAssessment();
  });

  it('keeps shipment details up to date when the context request fails', () => {
    render(<MaritimeContextPanel context={null} unavailable />);
    expect(screen.getByText('Maritime context is unavailable. Shipment details are still up to date.')).toBeInTheDocument();
  });
});
