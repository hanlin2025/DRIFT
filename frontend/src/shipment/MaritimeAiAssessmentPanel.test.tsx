import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MaritimeAiAssessmentPanel } from './MaritimeAiAssessmentPanel';
import type { MaritimeAiAssessment, MaritimeAiBusinessSnapshot, MaritimeAiFact, MaritimeAiRiskSnapshot } from './api';

const observedFact: MaritimeAiFact = {
  factReference: 'evidence:mpa-approved', factType: 'RETRIEVED_EVIDENCE', subject: 'Synthetic mother', eventType: 'EXPECTED_ARRIVAL',
  timestamp: '2026-10-29T02:00:00+08:00', previousValue: '2026-10-29T01:00:00+08:00', currentValue: '2026-10-29T02:00:00+08:00',
  evidenceId: 'mpa-approved', sourceType: 'MPA_ETA_REVISION', sourceMode: 'RETROSPECTIVE_ANALYSIS', associationScope: 'VESSEL_OBSERVATION',
  citation: 'approved MPA citation', observedAt: '2026-10-29T02:01:00Z', retrievedAtUtc: '2026-10-29T02:02:00Z',
};

const availablePlannedRisk: MaritimeAiRiskSnapshot = {
  assessed: true, severity: 'HIGH', selectedWindowBasis: 'PLANNED_SHIPMENT_SCHEDULE', selectedWindowState: 'AVAILABLE',
  windowSeconds: 28_800, missedBySeconds: null, estimateFreshness: 'UNAVAILABLE', estimateUnavailableCode: 'NO_POSITION',
  unavailableReason: null, calculationLimitations: ['No AIS position was available; the planned schedule was used.'],
  canonicalFactReferences: ['risk:CONNECTION_SEVERITY', 'risk:PLANNED_CONNECTION_WINDOW', 'risk:ARRIVAL_ESTIMATE_NOT_USED', 'risk:ARRIVAL_ESTIMATE_FRESHNESS'],
};

const assessment: MaritimeAiAssessment = {
  assessmentId: 'verified-capture', state: 'GENERATED', validationStatus: 'VALID', bundleVersion: 'cdg246.maritime-evidence-bundle.v1', bundleHash: 'a'.repeat(64), assessmentCutoffUtc: '2026-10-27T16:00:00Z',
  provider: 'openai-responses (verified capture replay)', model: 'gpt-4.1-mini', promptVersion: 'cdg247.evidence.v11.cdg260-risk-review.1', generatedAtUtc: '2026-11-01T09:00:00Z',
  interpretation: {
    summary: 'Generic orientation only; it does not describe business impact.',
    claims: [
      { statement: 'The observed mother-arrival revision is recorded for schedule review.', evidenceIds: ['mpa-approved'], applicability: 'CONTEXTUAL', vesselRole: 'MOTHER', observationType: 'EXPECTED_ARRIVAL', associationScope: 'VESSEL_OBSERVATION', factReferences: ['evidence:mpa-approved', 'schedule:MOTHER:PLANNED_ARRIVAL'], relationshipType: 'SCHEDULE_REVIEW' },
      { statement: 'The documented arrival revision warrants review alongside the backend HIGH planned connection window; the AIS estimate is unavailable.', evidenceIds: ['mpa-approved'], applicability: 'CONTEXTUAL', vesselRole: 'MOTHER', observationType: 'EXPECTED_ARRIVAL', associationScope: 'VESSEL_OBSERVATION', factReferences: ['evidence:mpa-approved', 'risk:CONNECTION_SEVERITY', 'risk:PLANNED_CONNECTION_WINDOW', 'risk:ARRIVAL_ESTIMATE_NOT_USED', 'risk:ARRIVAL_ESTIMATE_FRESHNESS'], relationshipType: 'RISK_BUFFER_REVIEW', riskReview: { severity: 'HIGH', selectedWindowBasis: 'PLANNED_SHIPMENT_SCHEDULE', selectedWindowState: 'AVAILABLE', estimateFreshness: 'UNAVAILABLE' } },
    ],
    uncertainties: ['The maritime observation does not establish a delay.'], followUpConsiderations: ["Verify the carrier's ETA with the operations team."], evidenceSufficient: true, formalRecommendationCode: null,
  },
  limitation: null,
  deterministicSummary: {
    summaryVersion: 'cdg247.deterministic-facts.v3.risk-snapshot', label: 'Backend-owned facts.',
    shipmentScheduleFacts: [{ factReference: 'schedule:MOTHER:PLANNED_ARRIVAL', factType: 'SHIPMENT_SCHEDULE', subject: 'Stored mother vessel', eventType: 'PLANNED_ARRIVAL', timestamp: '2026-10-29T02:00:00+08:00', previousValue: null, currentValue: null, evidenceId: null, sourceType: 'SHIPMENT_PLAN', sourceMode: 'DETERMINISTIC_CONTEXT', associationScope: 'SCHEDULE_CONTEXT', citation: 'Shipment planning context; not an independently retrieved vessel movement.' }],
    evidenceFacts: [observedFact], limitations: ['Matching IMO verifies vessel identity only.'], connectionRisk: availablePlannedRisk,
  },
};

const recordedDeadline = '2026-11-01T10:00:00Z';
const availableBusinessSnapshot: MaritimeAiBusinessSnapshot = {
  context: {
    deadline: { state: 'RECORDED', instant: recordedDeadline, recordedTransshipmentPort: 'Singapore', applicableToCurrentPort: true },
    comparison: {
      state: 'AVAILABLE', outcome: 'EXCEEDS_DEADLINE', unavailableReason: null,
      estimatedArrivalAt: '2026-11-01T11:00:00Z', comparedDeadline: recordedDeadline, recordedTransshipmentPort: 'Singapore',
    },
  },
  canonicalFactReferences: [
    'business:TRANSSHIPMENT_ARRIVAL_DEADLINE', 'business:TRANSSHIPMENT_ARRIVAL_DEADLINE_PORT',
    'business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY', 'comparison:DELAY_OUTCOME',
    'comparison:ESTIMATED_ARRIVAL', 'comparison:RECORDED_DEADLINE', 'comparison:RECORDED_TRANSSHIPMENT_PORT',
  ],
};
const businessClaim = {
  statement: 'The predicted arrival exceeds the applicable transshipment-arrival deadline.',
  evidenceIds: ['mpa-approved'], applicability: 'CONTEXTUAL', vesselRole: 'MOTHER' as const,
  observationType: 'EXPECTED_ARRIVAL', associationScope: 'VESSEL_OBSERVATION',
  factReferences: ['evidence:mpa-approved', 'business:TRANSSHIPMENT_ARRIVAL_DEADLINE', 'comparison:DELAY_OUTCOME'],
  relationshipType: 'DELAY_COMPARISON_REVIEW' as const,
};

function withBusinessSnapshot(snapshot: MaritimeAiBusinessSnapshot = availableBusinessSnapshot, claim = businessClaim): MaritimeAiAssessment {
  return {
    ...assessment,
    interpretation: { ...assessment.interpretation!, claims: [...assessment.interpretation!.claims, claim] },
    deterministicSummary: { ...assessment.deterministicSummary!, businessContext: snapshot },
  };
}

function renderPanel(value: MaritimeAiAssessment, onGenerate = vi.fn()) {
  return { ...render(<MaritimeAiAssessmentPanel assessments={[value]} unavailable={false} generating={false} onGenerate={onGenerate} />), onGenerate };
}

describe('MaritimeAiAssessmentPanel', () => {
  it('separates observed maritime evidence from the validated risk-review implication', () => {
    renderPanel(assessment);
    const evidence = screen.getByRole('region', { name: 'Observed maritime evidence' });
    const risk = screen.getByRole('region', { name: 'Tentative risk-review implications' });
    expect(within(evidence).getByText((_, node) => node?.tagName === 'P' && node.textContent === 'MPA vessel-arrival revision · Expected Arrival')).toBeInTheDocument();
    expect(within(evidence).getByText('2026-10-29T01:00:00+08:00 → 2026-10-29T02:00:00+08:00')).toBeInTheDocument();
    expect(within(evidence).getByText('The observed mother-arrival revision is recorded for schedule review.')).toBeInTheDocument();
    expect(within(risk).getByText('The documented arrival revision warrants review alongside the backend HIGH planned connection window; the AIS estimate is unavailable.')).toBeInTheDocument();
    const details = screen.getByText('Assessment and evidence details').closest('details');
    expect(details).not.toBeNull();
    expect(within(details!).getByText('Generic orientation only; it does not describe business impact.')).toBeInTheDocument();
    expect(screen.queryByText(/What changed|Why it matters/)).not.toBeInTheDocument();
  });

  it('renders the persisted planned-window snapshot at assessment time with a valid duration', () => {
    renderPanel(assessment);
    const risk = screen.getByRole('region', { name: 'Assessment-time connection risk' });
    expect(within(risk).getByText(/Risk at assessment time · 2026-11-01T09:00:00Z/)).toBeInTheDocument();
    expect(within(risk).getByText('Planned shipment schedule')).toBeInTheDocument();
    expect(within(risk).getByText('Selected connection window')).toBeInTheDocument();
    expect(within(risk).getByText('8 hours')).toBeInTheDocument();
    expect(within(risk).getByText('Available')).toBeInTheDocument();
    expect(within(risk).getByText('Unavailable')).toBeInTheDocument();
    expect(within(risk).getByText(/does not describe the shipment's current operational state/)).toBeInTheDocument();
  });

  it('shows distinct saved risk snapshots for two assessments with the same maritime evidence', () => {
    const lowAssessment: MaritimeAiAssessment = {
      ...assessment,
      assessmentId: 'shared-evidence-low-window',
      generatedAtUtc: '2026-11-01T10:00:00Z',
      interpretation: {
        ...assessment.interpretation!,
        claims: [
          assessment.interpretation!.claims[0],
          {
            ...assessment.interpretation!.claims[1],
            statement: 'The documented arrival revision warrants review alongside the backend LOW planned connection window; the AIS estimate is unavailable.',
            riskReview: { severity: 'LOW', selectedWindowBasis: 'PLANNED_SHIPMENT_SCHEDULE', selectedWindowState: 'AVAILABLE', estimateFreshness: 'UNAVAILABLE' },
          },
        ],
      },
      deterministicSummary: {
        ...assessment.deterministicSummary!,
        connectionRisk: { ...availablePlannedRisk, severity: 'LOW', windowSeconds: 108_000 },
      },
    };

    render(<>
      <MaritimeAiAssessmentPanel assessments={[assessment]} unavailable={false} generating={false} onGenerate={vi.fn()} />
      <MaritimeAiAssessmentPanel assessments={[lowAssessment]} unavailable={false} generating={false} onGenerate={vi.fn()} />
    </>);

    const panels = screen.getAllByRole('region', { name: 'AI maritime interpretation' });
    expect(panels).toHaveLength(2);
    const highRisk = within(panels[0]).getByRole('region', { name: 'Assessment-time connection risk' });
    const lowRisk = within(panels[1]).getByRole('region', { name: 'Assessment-time connection risk' });
    expect(within(highRisk).getByText('High')).toBeInTheDocument();
    expect(within(highRisk).getByText('8 hours')).toBeInTheDocument();
    expect(within(lowRisk).getByText('Low')).toBeInTheDocument();
    expect(within(lowRisk).getByText('1 day 6 hours')).toBeInTheDocument();
    expect(within(highRisk).getByText(/2026-11-01T09:00:00Z/)).toBeInTheDocument();
    expect(within(lowRisk).getByText(/2026-11-01T10:00:00Z/)).toBeInTheDocument();
    expect(within(panels[0]).getByText('2026-10-29T01:00:00+08:00 → 2026-10-29T02:00:00+08:00').textContent)
      .toEqual(within(panels[1]).getByText('2026-10-29T01:00:00+08:00 → 2026-10-29T02:00:00+08:00').textContent);
    expect(within(panels[0]).getByText(/Citation: approved MPA citation/).textContent)
      .toEqual(within(panels[1]).getByText(/Citation: approved MPA citation/).textContent);
    expect(within(panels[0]).getByText(/This snapshot does not describe the shipment's current operational state/)).toBeInTheDocument();
    expect(within(panels[1]).getByText(/This snapshot does not describe the shipment's current operational state/)).toBeInTheDocument();
  });

  it('distinguishes an AIS-based non-positive remaining window without saying a connection was missed', () => {
    const aisAssessment: MaritimeAiAssessment = {
      ...assessment,
      interpretation: {
        ...assessment.interpretation!,
        claims: [{
          ...assessment.interpretation!.claims[1],
          statement: 'The documented arrival revision warrants review alongside the backend CRITICAL AIS-estimated remaining connection window; the estimate is available.',
          riskReview: { severity: 'CRITICAL', selectedWindowBasis: 'AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE', selectedWindowState: 'NO_REMAINING_WINDOW', estimateFreshness: 'AVAILABLE' },
        }],
      },
      deterministicSummary: {
        ...assessment.deterministicSummary!,
        connectionRisk: {
          ...availablePlannedRisk, severity: 'CRITICAL', selectedWindowBasis: 'AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE',
          selectedWindowState: 'MISSED', windowSeconds: null, missedBySeconds: 5_400, estimateFreshness: 'AVAILABLE',
          calculationLimitations: ['The AIS-estimated arrival was used; the calculation does not confirm a missed transfer.'],
          canonicalFactReferences: ['risk:CONNECTION_SEVERITY', 'risk:REMAINING_CONNECTION_WINDOW', 'risk:AIS_ESTIMATED_ARRIVAL', 'risk:ARRIVAL_ESTIMATE_FRESHNESS'],
        },
      },
    };
    renderPanel(aisAssessment);
    const risk = screen.getByRole('region', { name: 'Assessment-time connection risk' });
    expect(within(risk).getByText('AIS-estimated remaining connection window')).toBeInTheDocument();
    expect(within(risk).getByText('No positive remaining window (calculated)')).toBeInTheDocument();
    expect(within(risk).getByText('Calculated overrun')).toBeInTheDocument();
    expect(within(risk).getByText('1 hour 30 minutes')).toBeInTheDocument();
    expect(screen.getByText(/not confirmation that a connection was missed/)).toBeInTheDocument();
  });

  it('shows stale estimates and omits absent or invalid window durations', () => {
    const staleAssessment: MaritimeAiAssessment = {
      ...assessment,
      interpretation: {
        ...assessment.interpretation!,
        claims: [
          assessment.interpretation!.claims[0],
          {
            ...assessment.interpretation!.claims[1],
            statement: 'The documented arrival revision warrants review alongside the backend HIGH planned connection window; the AIS estimate is stale.',
            riskReview: { severity: 'HIGH', selectedWindowBasis: 'PLANNED_SHIPMENT_SCHEDULE', selectedWindowState: 'INVALID', estimateFreshness: 'STALE' },
          },
        ],
      },
      deterministicSummary: {
        ...assessment.deterministicSummary!,
        connectionRisk: { ...availablePlannedRisk, selectedWindowState: 'INVALID', windowSeconds: null, estimateFreshness: 'STALE', calculationLimitations: ['The stored AIS estimate was stale and was not used.'] },
      },
    };
    renderPanel(staleAssessment);
    const risk = screen.getByRole('region', { name: 'Assessment-time connection risk' });
    expect(within(risk).getByText('Stale')).toBeInTheDocument();
    expect(within(risk).getByText('Invalid window')).toBeInTheDocument();
    expect(within(risk).queryByText('Selected connection window')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Assessment and evidence details'));
    const riskDetails = screen.getByRole('region', { name: 'Assessment-time risk calculation details' });
    expect(within(riskDetails).queryByText('Selected window duration')).not.toBeInTheDocument();
    expect(within(riskDetails).queryByText('Calculated overrun')).not.toBeInTheDocument();
  });

  it('keeps legacy evidence-only assessments readable without riskReview or a risk snapshot', () => {
    const legacy: MaritimeAiAssessment = {
      ...assessment,
      provider: 'openai-responses',
      interpretation: { ...assessment.interpretation!, summary: 'Legacy summary.', claims: [assessment.interpretation!.claims[0]] },
      deterministicSummary: null,
    };
    renderPanel(legacy);
    expect(screen.getByRole('region', { name: 'Observed maritime evidence' })).toBeInTheDocument();
    expect(screen.getByText(/without a validated connection risk-review claim/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Why this shipment warrants review' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Business implications' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Assessment-time importer business context' })).not.toBeInTheDocument();
    expect(screen.getByText(/Provider-generated assessment from openai-responses/)).toBeInTheDocument();
  });

  it('shows a validated deadline comparison under Business implications and its saved facts in details', () => {
    renderPanel(withBusinessSnapshot());
    const evidence = screen.getByRole('region', { name: 'Observed maritime evidence' });
    const business = screen.getByRole('region', { name: 'Business implications' });
    expect(within(evidence).queryByText(businessClaim.statement)).not.toBeInTheDocument();
    expect(within(business).getByText('Validated importer deadline comparison')).toBeInTheDocument();
    expect(within(business).getByText(businessClaim.statement)).toBeInTheDocument();

    fireEvent.click(screen.getByText('Assessment and evidence details'));
    const details = screen.getByRole('region', { name: 'Assessment-time importer business context' });
    expect(within(details).getByText('Recorded')).toBeInTheDocument();
    expect(within(details).getAllByText(recordedDeadline)).toHaveLength(2);
    expect(within(details).getAllByText('Singapore')).toHaveLength(2);
    expect(within(details).getByText('Applies to this shipment’s transshipment port')).toBeInTheDocument();
    expect(within(details).getByText('Exceeds Deadline')).toBeInTheDocument();
    expect(within(details).getByText('2026-11-01T11:00:00Z')).toBeInTheDocument();
    expect(within(details).getByText(/business:TRANSSHIPMENT_ARRIVAL_DEADLINE/)).toBeInTheDocument();
    expect(within(details).getByText(/comparison:DELAY_OUTCOME/)).toBeInTheDocument();
  });

  it('displays NO_PREDICTED_IMPACT only when the saved comparison is available with that outcome', () => {
    const snapshot: MaritimeAiBusinessSnapshot = {
      ...availableBusinessSnapshot,
      context: {
        ...availableBusinessSnapshot.context!,
        comparison: {
          state: 'AVAILABLE', outcome: 'NO_PREDICTED_IMPACT', unavailableReason: null,
          estimatedArrivalAt: '2026-11-01T11:00:00Z', comparedDeadline: recordedDeadline, recordedTransshipmentPort: 'Singapore',
        },
      },
    };
    const value = withBusinessSnapshot(snapshot, { ...businessClaim, statement: 'No predicted impact is shown against the applicable transshipment-arrival deadline.' });
    renderPanel(value);
    fireEvent.click(screen.getByText('Assessment and evidence details'));
    expect(screen.getByText('No Predicted Impact')).toBeInTheDocument();
    expect(screen.queryByText('Comparison unavailable reason')).not.toBeInTheDocument();
  });

  it('shows a missing deadline as unavailable rather than as no predicted impact', () => {
    const snapshot: MaritimeAiBusinessSnapshot = {
      context: {
        deadline: { state: 'NOT_RECORDED', instant: null, recordedTransshipmentPort: null, applicableToCurrentPort: null },
        comparison: { state: 'UNAVAILABLE', outcome: null, unavailableReason: 'MISSING_CONSTRAINT', estimatedArrivalAt: '2026-11-01T11:00:00Z', comparedDeadline: null, recordedTransshipmentPort: null },
      },
      canonicalFactReferences: ['business:TRANSSHIPMENT_ARRIVAL_DEADLINE_NOT_RECORDED', 'comparison:UNAVAILABLE_REASON'],
    };
    const value = withBusinessSnapshot(snapshot, {
      ...businessClaim,
      statement: 'No current importer deadline is recorded, so the comparison is unavailable.',
      factReferences: ['evidence:mpa-approved', 'business:TRANSSHIPMENT_ARRIVAL_DEADLINE_NOT_RECORDED', 'comparison:UNAVAILABLE_REASON'],
    });
    renderPanel(value);
    fireEvent.click(screen.getByText('Assessment and evidence details'));
    const details = screen.getByRole('region', { name: 'Assessment-time importer business context' });
    expect(within(details).getByText('No deadline recorded at assessment time')).toBeInTheDocument();
    expect(within(details).getByText('Missing Constraint')).toBeInTheDocument();
    expect(within(details).queryByText('No Predicted Impact')).not.toBeInTheDocument();
  });

  it('preserves an inapplicable different-port deadline and its unavailable comparison reason', () => {
    const snapshot: MaritimeAiBusinessSnapshot = {
      context: {
        deadline: { state: 'RECORDED', instant: recordedDeadline, recordedTransshipmentPort: 'Tanjung Pelepas', applicableToCurrentPort: false },
        comparison: { state: 'UNAVAILABLE', outcome: null, unavailableReason: 'INCOMPATIBLE_MILESTONE', estimatedArrivalAt: '2026-11-01T11:00:00Z', comparedDeadline: recordedDeadline, recordedTransshipmentPort: 'Tanjung Pelepas' },
      },
      canonicalFactReferences: ['business:TRANSSHIPMENT_ARRIVAL_DEADLINE', 'business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY', 'comparison:UNAVAILABLE_REASON'],
    };
    const value = withBusinessSnapshot(snapshot, {
      ...businessClaim,
      statement: 'The recorded deadline is for a different transshipment port, so comparison is unavailable.',
      factReferences: ['evidence:mpa-approved', 'business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY', 'comparison:UNAVAILABLE_REASON'],
    });
    renderPanel(value);
    fireEvent.click(screen.getByText('Assessment and evidence details'));
    const details = screen.getByRole('region', { name: 'Assessment-time importer business context' });
    expect(within(details).getAllByText('Tanjung Pelepas')).toHaveLength(2);
    expect(within(details).getByText('Does not apply to this shipment’s transshipment port')).toBeInTheDocument();
    expect(within(details).getByText('Incompatible Milestone')).toBeInTheDocument();
    expect(within(details).queryByText('No Predicted Impact')).not.toBeInTheDocument();
  });

  it.each([
    ['missing', 'MISSING_PREDICTION' as const, null],
    ['stale', 'STALE_PREDICTION' as const, '2026-11-01T11:00:00Z'],
  ])('keeps a %s estimate comparison unavailable without inventing an outcome', (label, reason, estimatedArrivalAt) => {
    const snapshot: MaritimeAiBusinessSnapshot = {
      ...availableBusinessSnapshot,
      context: {
        ...availableBusinessSnapshot.context!,
        comparison: { ...availableBusinessSnapshot.context!.comparison!, state: 'UNAVAILABLE', outcome: null, unavailableReason: reason, estimatedArrivalAt },
      },
      canonicalFactReferences: ['business:TRANSSHIPMENT_ARRIVAL_DEADLINE', 'comparison:UNAVAILABLE_REASON'],
    };
    const value = withBusinessSnapshot(snapshot, {
      ...businessClaim,
      statement: `The ${label} estimate leaves the deadline comparison unavailable.`,
      factReferences: ['evidence:mpa-approved', 'business:TRANSSHIPMENT_ARRIVAL_DEADLINE', 'comparison:UNAVAILABLE_REASON'],
    });
    renderPanel(value);
    fireEvent.click(screen.getByText('Assessment and evidence details'));
    const details = screen.getByRole('region', { name: 'Assessment-time importer business context' });
    expect(within(details).getByText(reason === 'STALE_PREDICTION' ? 'Stale Prediction' : 'Missing Prediction')).toBeInTheDocument();
    expect(within(details).queryByText('No Predicted Impact')).not.toBeInTheDocument();
  });

  it('keeps an older assessment deadline after the shipment deadline changes', () => {
    const savedDeadline = '2026-10-31T10:00:00Z';
    const currentShipmentDeadline = '2026-11-03T10:00:00Z';
    const snapshot: MaritimeAiBusinessSnapshot = {
      ...availableBusinessSnapshot,
      context: {
        ...availableBusinessSnapshot.context!,
        deadline: { ...availableBusinessSnapshot.context!.deadline!, instant: savedDeadline },
        comparison: { ...availableBusinessSnapshot.context!.comparison!, comparedDeadline: savedDeadline },
      },
    };
    renderPanel(withBusinessSnapshot(snapshot));
    fireEvent.click(screen.getByText('Assessment and evidence details'));
    const details = screen.getByRole('region', { name: 'Assessment-time importer business context' });
    expect(within(details).getAllByText(savedDeadline)).toHaveLength(2);
    expect(within(details).queryByText(currentShipmentDeadline)).not.toBeInTheDocument();
  });

  it('does not show business claims for failed or abstained assessments', () => {
    const value = withBusinessSnapshot();
    const failed = { ...value, state: 'FAILED' as const, validationStatus: 'UNSTRUCTURED_FACTUAL_DETAIL', limitation: 'Validation rejected the output.' };
    const { rerender } = render(<MaritimeAiAssessmentPanel assessments={[failed]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.queryByRole('region', { name: 'Business implications' })).not.toBeInTheDocument();
    expect(screen.queryByText(businessClaim.statement)).not.toBeInTheDocument();

    const abstained = { ...value, state: 'ABSTAINED' as const, validationStatus: 'INSUFFICIENT_EVIDENCE', limitation: 'Evidence was insufficient.' };
    rerender(<MaritimeAiAssessmentPanel assessments={[abstained]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.queryByRole('region', { name: 'Business implications' })).not.toBeInTheDocument();
    expect(screen.queryByText(businessClaim.statement)).not.toBeInTheDocument();
  });

  it('distinguishes a verified provider-capture replay from an actual provider-generated assessment and a stub', () => {
    const { rerender } = render(<MaritimeAiAssessmentPanel assessments={[assessment]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.getByText(/Verified OpenAI provider-capture replay/)).toBeInTheDocument();
    expect(screen.queryByText(/Provider-generated assessment from/)).not.toBeInTheDocument();

    const actual = { ...assessment, provider: 'openai-responses' };
    rerender(<MaritimeAiAssessmentPanel assessments={[actual]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.getByText(/Provider-generated assessment from openai-responses/)).toBeInTheDocument();
    expect(screen.queryByText(/provider-capture replay/)).not.toBeInTheDocument();

    const stub = { ...assessment, provider: 'controlled-replay-stub' };
    rerender(<MaritimeAiAssessmentPanel assessments={[stub]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.getByText(/Controlled synthetic stub assessment/)).toBeInTheDocument();
    expect(screen.queryByText(/Provider-generated assessment from/)).not.toBeInTheDocument();
  });

  it('never displays failed or abstained claims as validated reasoning', () => {
    const unsafeInterpretation = { ...assessment.interpretation!, summary: 'Unsafe summary.', claims: [{ ...assessment.interpretation!.claims[1], statement: 'This unvalidated risk claim must stay hidden.' }] };
    const failed = { ...assessment, state: 'FAILED' as const, validationStatus: 'UNSTRUCTURED_FACTUAL_DETAIL', interpretation: unsafeInterpretation, limitation: 'Validation rejected the output.' };
    const { rerender } = render(<MaritimeAiAssessmentPanel assessments={[failed]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'Validated interpretation unavailable' })).toBeInTheDocument();
    expect(screen.getByText('Validation rejected the output.')).toBeInTheDocument();
    expect(screen.queryByText('This unvalidated risk claim must stay hidden.')).not.toBeInTheDocument();
    expect(screen.queryByText('Unsafe summary.')).not.toBeInTheDocument();

    const abstained = { ...failed, state: 'ABSTAINED' as const, validationStatus: 'INSUFFICIENT_EVIDENCE', limitation: 'Evidence was insufficient.' };
    rerender(<MaritimeAiAssessmentPanel assessments={[abstained]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'No validated interpretation was produced' })).toBeInTheDocument();
    expect(screen.getByText('Evidence was insufficient.')).toBeInTheDocument();
    expect(screen.queryByText('This unvalidated risk claim must stay hidden.')).not.toBeInTheDocument();
  });

  it('keeps citations, IDs, canonical references, and expandable evidence details accessible', () => {
    renderPanel(assessment);
    const summary = screen.getByText('Assessment and evidence details');
    const details = summary.closest('details');
    expect(details).not.toBeNull();
    expect(details).not.toHaveAttribute('open');
    expect(summary.closest('summary')).not.toBeNull();
    fireEvent.click(summary);
    expect(details).toHaveAttribute('open');
    expect(screen.getByText(/Citation: approved MPA citation/)).toBeInTheDocument();
    expect(screen.getByText(/evidence ID mpa-approved/)).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Assessment-time risk calculation details' })).getByText(/risk:PLANNED_CONNECTION_WINDOW/)).toBeInTheDocument();
    expect(screen.getByText(/prompt:/)).toBeInTheDocument();
    expect(screen.getByText(/Evidence freshness was not included/)).toBeInTheDocument();
  });

  it('handles empty history and assessment service unavailability safely', () => {
    const onGenerate = vi.fn();
    const { rerender } = render(<MaritimeAiAssessmentPanel assessments={[]} unavailable={false} generating={false} onGenerate={onGenerate} />);
    expect(screen.getByText('No AI maritime interpretation has been generated for this shipment.')).toBeInTheDocument();
    rerender(<MaritimeAiAssessmentPanel assessments={[]} unavailable={true} generating={false} onGenerate={onGenerate} />);
    expect(screen.getByText(/AI maritime interpretation is unavailable/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Generate/ })).not.toBeInTheDocument();
  });
});
