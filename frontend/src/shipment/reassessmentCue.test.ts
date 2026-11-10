import { describe, expect, it } from 'vitest';
import { type MaritimeContext, type MaritimeEvidence } from './api';
import { REASSESSMENT_DOES_NOT_CHANGE, reassessmentCue } from './reassessmentCue';

function evidence(overrides: Partial<MaritimeEvidence> = {}): MaritimeEvidence {
  return {
    sourceType: 'MPA_ETA_REVISION', sourceMode: 'RECORDED_REPLAY', evidenceId: 'eta-1', title: 'Recorded revision',
    sourceIdentifier: 'MPA OCEANS-X', sourceUrl: '', citation: 'Manifest citation',
    sourceEventAt: '2026-10-29T02:00:00+08:00', observedAt: '2026-10-26T15:46:03Z', retrievedAtUtc: '2026-10-26T15:46:03Z',
    freshness: 'RECORDED_REPLAY', previousValue: '', currentValue: '', changeMagnitude: '',
    activityCompatibility: 'UNKNOWN', locationCompatibility: 'UNKNOWN', timeCompatibility: 'UNKNOWN', compatibilityState: 'UNKNOWN',
    evidenceSufficient: true, humanReviewRequired: false, shipmentAssociationConfidence: 'UNVERIFIED_IMO_ONLY',
    limitation: 'Recorded replay only.',
    ...overrides,
  };
}

function context(overrides: Partial<MaritimeContext> = {}, items: MaritimeEvidence[] = [evidence()]): MaritimeContext {
  return {
    contractVersion: 'cdg246.maritime-evidence-bundle.v1', enabled: true, sourceMode: 'RECORDED_REPLAY',
    assessmentCutoffUtc: '2026-10-28T00:00:00Z', shipmentAssociationConfidence: 'UNVERIFIED_IMO_ONLY',
    limitation: 'Human review is required. This bundle is a read-only replay/demo and does not change ETA, shipment, risk, alerts, or operational decisions.',
    evidence: items, notices: [], aiExplanation: null, suggestedActions: null,
    ...overrides,
  };
}

describe('reassessment cue', () => {
  it('keeps a disabled bundle not authorized and identifies the maritime evidence workflow', () => {
    const result = reassessmentCue(context({ enabled: false, sourceMode: 'DISABLED', evidence: [] }));
    expect(result).toMatchObject({ state: 'denied', headline: 'Not authorized' });
    expect(result.detail).toContain('No maritime context evidence was gathered or sent');
    expect(result.detail).toContain(REASSESSMENT_DOES_NOT_CHANGE);
    expect(result.detail).not.toMatch(/\b(GENERATED|VALID)\b/);
  });

  it('marks a recorded change as updated evidence that is still waiting for review', () => {
    const result = reassessmentCue(context({}, [evidence({ changeMagnitude: '+180 minutes', previousValue: 'before', currentValue: 'after', humanReviewRequired: true })]));
    expect(result.state).toBe('updated');
    expect(result.detail).toContain('AI assessment status is shown in the separate AI Interpretation panel');
    expect(result.detail).not.toMatch(/\b(GENERATED|VALID)\b/);
  });

  it('treats a null change magnitude as unrecorded rather than as a change', () => {
    const result = reassessmentCue(context({}, [evidence({ changeMagnitude: null })]));
    expect(result.state).toBe('stale');
  });

  it('marks review without a recorded change as pending', () => {
    const result = reassessmentCue(context({}, [evidence({ humanReviewRequired: true, sourceType: 'MPA_OFFICIAL_NOTICE', sourceMode: 'SYNTHETIC_DEMONSTRATION' })]));
    expect(result.state).toBe('pending');
  });

  it('marks recorded evidence without a live reading as stale', () => {
    const result = reassessmentCue(context());
    expect(result.state).toBe('stale');
    expect(result.detail).toContain('not a live reassessment');
  });

  it('marks a live applicable reading as evidence available without producing an assessment', () => {
    const result = reassessmentCue(context({}, [evidence({ sourceMode: 'LIVE', freshness: 'APPLICABLE', shipmentAssociationConfidence: 'UNKNOWN' })]));
    expect(result).toMatchObject({ state: 'available', headline: 'Evidence available' });
    expect(result.detail).toContain('AI assessment status is shown in the separate AI Interpretation panel');
  });

  it('keeps an unavailable source as a failure and identifies the separate AI workflow', () => {
    const result = reassessmentCue(context({}, [evidence({
      sourceType: 'NEA_WEATHER', sourceMode: 'UNAVAILABLE', freshness: 'UNAVAILABLE', evidenceSufficient: false,
      humanReviewRequired: true, shipmentAssociationConfidence: 'UNAVAILABLE', evidenceId: 'weather-none',
    })]));
    expect(result.state).toBe('failed');
    expect(result.detail).toContain('AI assessment status is shown in the separate AI Interpretation panel');
    expect(result.detail).not.toMatch(/\b(GENERATED|VALID)\b/);
  });

  it('does not let a failed source hide an earlier readable change', () => {
    const result = reassessmentCue(context({}, [
      evidence({ changeMagnitude: '+180 minutes', humanReviewRequired: true }),
      evidence({ sourceType: 'NEA_WEATHER', sourceMode: 'UNAVAILABLE', evidenceId: 'weather-none', evidenceSufficient: false, shipmentAssociationConfidence: 'UNAVAILABLE' }),
    ]));
    expect(result.state).toBe('updated');
  });
});
