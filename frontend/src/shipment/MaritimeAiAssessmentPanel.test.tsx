import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MaritimeAiAssessmentPanel } from './MaritimeAiAssessmentPanel';
import type { MaritimeAiAssessment } from './api';

const assessment: MaritimeAiAssessment = {
  assessmentId: 'verified-capture', state: 'GENERATED', validationStatus: 'VALID', bundleVersion: 'cdg246.maritime-evidence-bundle.v1', bundleHash: 'a'.repeat(64), assessmentCutoffUtc: '2026-10-27T16:00:00Z',
  provider: 'openai-responses (verified capture replay)', model: 'gpt-4.1-mini', promptVersion: 'cdg247.evidence.v9.documented-revision-grounding.1', generatedAtUtc: '2026-11-01T09:00:00Z',
  interpretation: { summary: 'Review the grounded interpretation.', claims: [{ statement: 'The documented revision warrants schedule review.', evidenceIds: ['mpa-approved'], applicability: 'UNCERTAIN', factReferences: ['evidence:mpa-approved'], relationshipType: 'SCHEDULE_REVIEW' }], uncertainties: ['Shipment association remains unverified.'], followUpConsiderations: [], evidenceSufficient: true, formalRecommendationCode: null }, limitation: null,
  deterministicSummary: { summaryVersion: 'v2', label: 'Backend-owned facts.', shipmentScheduleFacts: [], evidenceFacts: [{ factReference: 'evidence:mpa-approved', factType: 'RETRIEVED_EVIDENCE', subject: null, eventType: 'EXPECTED_ARRIVAL', timestamp: '2026-10-29T02:00:00+08:00', previousValue: null, currentValue: null, evidenceId: 'mpa-approved', sourceType: 'MPA_ETA_REVISION', sourceMode: 'RETROSPECTIVE_ANALYSIS', associationScope: 'VESSEL_OBSERVATION', citation: 'approved citation', observedAt: null, retrievedAtUtc: '2026-10-26T15:46:03Z' }], limitations: ['Matching IMO verifies vessel identity only.'] }
};

describe('MaritimeAiAssessmentPanel', () => {
  it('shows verified-capture provenance, provider metadata, citations, and limitations without calling generation', () => {
    render(<MaritimeAiAssessmentPanel assessments={[assessment]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.getByText(/verified OpenAI provider-capture replay/i)).toBeInTheDocument();
    expect(screen.getByText(/gpt-4.1-mini/)).toBeInTheDocument();
    expect(screen.getByText(/cdg247.evidence.v9.documented-revision-grounding.1/)).toBeInTheDocument();
    expect(screen.getByText(/generated 2026-11-01T09:00:00Z/)).toBeInTheDocument();
    expect(screen.getByText(/Assessment and evidence details/)).toBeInTheDocument();
  });

  it('does not present a failed assessment as generated reasoning', () => {
    render(<MaritimeAiAssessmentPanel assessments={[{ ...assessment, state: 'FAILED', validationStatus: 'UNSTRUCTURED_FACTUAL_DETAIL', interpretation: null, limitation: 'Validation rejected the output.' }]} unavailable={false} generating={false} onGenerate={vi.fn()} />);
    expect(screen.getByText('Validation rejected the output.')).toBeInTheDocument();
    expect(screen.queryByText('Review the grounded interpretation.')).not.toBeInTheDocument();
  });
});
