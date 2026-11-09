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
    return cue('denied', 'Maritime reassessment is off. No assessment was produced, and nothing was sent.');
  }
  if (context.evidence.length === 0) {
    return cue('empty', 'No validated result is recorded.');
  }
  const open = context.evidence.filter(readable);
  if (open.length === 0) {
    return cue('failed', 'A source could not be read. No validated result was stored in its place.');
  }
  if (open.some(changed)) {
    return cue('updated', 'Recorded evidence changed and is waiting for review. No validated result is recorded.');
  }
  if (context.evidence.some(item => item.humanReviewRequired)) {
    return cue('pending', 'A person needs to review this evidence. No validated result is recorded.');
  }
  if (open.some(live)) {
    return cue('available', 'Current evidence is available to review. No validated result is recorded.');
  }
  return cue('stale', 'This evidence is a recorded replay, not a live reassessment. No validated result is recorded.');
}
