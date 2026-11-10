import { type MaritimeContext, type MaritimeEvidence } from './api';

export const REASSESSMENT_DOES_NOT_CHANGE =
  'This does not change the shipment, ETA, risk, alerts, recommendation, or decision.';

export type ReassessmentState = 'denied' | 'empty' | 'failed' | 'updated' | 'pending' | 'available' | 'stale';

export type ReassessmentCue = {
  state: ReassessmentState;
  headline: string;
  detail: string;
};

const headline: Record<ReassessmentState, string> = {
  denied: 'Not authorized',
  empty: 'No evidence',
  failed: 'Source failed',
  updated: 'Updated evidence',
  pending: 'Pending review',
  available: 'Evidence available',
  stale: 'Stale recorded evidence',
};

const SEPARATE_AI_WORKFLOW = 'AI assessment status is shown in the separate AI Interpretation panel.';

function cue(state: ReassessmentState, detail: string): ReassessmentCue {
  return { state, headline: headline[state], detail: `${detail} ${REASSESSMENT_DOES_NOT_CHANGE}` };
}

function readable(item: MaritimeEvidence) {
  return item.sourceMode !== 'UNAVAILABLE';
}

function changed(item: MaritimeEvidence) {
  return item.changeMagnitude !== null && item.changeMagnitude.trim() !== '';
}

function live(item: MaritimeEvidence) {
  return item.sourceMode === 'LIVE' && item.freshness === 'APPLICABLE';
}

export function reassessmentCue(context: MaritimeContext | null): ReassessmentCue {
  if (!context?.enabled) {
    return cue('denied', 'Maritime reassessment is off. No maritime context evidence was gathered or sent.');
  }
  if (context.evidence.length === 0) {
    return cue('empty', `No evidence is available. ${SEPARATE_AI_WORKFLOW}`);
  }
  const open = context.evidence.filter(readable);
  if (open.length === 0) {
    return cue('failed', `A source could not be read. ${SEPARATE_AI_WORKFLOW}`);
  }
  if (open.some(changed)) {
    return cue('updated', `Recorded evidence changed and is waiting for review. ${SEPARATE_AI_WORKFLOW}`);
  }
  if (context.evidence.some(item => item.humanReviewRequired)) {
    return cue('pending', `A person needs to review this evidence. ${SEPARATE_AI_WORKFLOW}`);
  }
  if (open.some(live)) {
    return cue('available', `Current evidence is available to review. ${SEPARATE_AI_WORKFLOW}`);
  }
  return cue('stale', `This evidence is a recorded replay, not a live reassessment. ${SEPARATE_AI_WORKFLOW}`);
}
