export type RecommendationCode =
  | 'NO_ACTION'
  | 'MONITOR_CONSTRAINTS'
  | 'NOTE_TIMING'
  | 'REVIEW_PLAN'
  | 'WATCH_CONNECTION'
  | 'PREPARE_INTERVENTION'
  | 'CONFIRM_CONNECTION'
  | 'ACT_ON_CONNECTION';

export const RECOMMENDATION_CODES: readonly RecommendationCode[] = [
  'NO_ACTION',
  'MONITOR_CONSTRAINTS',
  'NOTE_TIMING',
  'REVIEW_PLAN',
  'WATCH_CONNECTION',
  'PREPARE_INTERVENTION',
  'CONFIRM_CONNECTION',
  'ACT_ON_CONNECTION',
];

export const RECOMMENDATION_RESPONSES: Record<RecommendationCode, { label: string; wording: string }> = {
  NO_ACTION: {
    label: 'No action',
    wording: 'No action is needed. The connection risk is low, and the prediction does not affect the importer\'s constraints.',
  },
  MONITOR_CONSTRAINTS: {
    label: 'Monitor',
    wording: 'Monitor the shipment. The connection risk is low, and the prediction affects the importer\'s constraints.',
  },
  NOTE_TIMING: {
    label: 'Note the timing',
    wording: 'Note the connection timing. The connection risk is medium, and the prediction does not affect the importer\'s constraints.',
  },
  REVIEW_PLAN: {
    label: 'Review the plan',
    wording: 'Review the transfer plan. The connection risk is medium, and the prediction affects the importer\'s constraints.',
  },
  WATCH_CONNECTION: {
    label: 'Watch the connection',
    wording: 'Watch the connection. The connection risk is high, and the prediction does not affect the importer\'s constraints.',
  },
  PREPARE_INTERVENTION: {
    label: 'Prepare an intervention',
    wording: 'Prepare an intervention. The connection risk is high, and the prediction affects the importer\'s constraints.',
  },
  CONFIRM_CONNECTION: {
    label: 'Confirm the connection',
    wording: 'Confirm the connection plan. The connection risk is critical, and the prediction does not affect the importer\'s constraints.',
  },
  ACT_ON_CONNECTION: {
    label: 'Act on the connection',
    wording: 'Act on this connection. The connection risk is critical, and the prediction affects the importer\'s constraints.',
  },
};

export const IMPACT_NOT_RETURNED = 'A recommendation is not available because the impact assessment has not been returned.';
