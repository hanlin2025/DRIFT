import { useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../signup/api';
import { recordRecommendationDecision, type RecommendationDecision, type RecommendationDecisionOutcome, type ShipmentRecommendation } from './api';
import { IMPACT_NOT_RETURNED, RECOMMENDATION_CODES, RECOMMENDATION_RESPONSES, type RecommendationCode } from './recommendationCatalog';
import { formatWhen } from './ShipmentForm';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';

export function RecommendationDecisionPanel({ token, shipmentId, recommendation, decision, archived, onSessionEnded, onRecorded }: {
  token: string;
  shipmentId: number;
  recommendation: ShipmentRecommendation | null | undefined;
  decision: RecommendationDecision | null | undefined;
  archived: boolean;
  onSessionEnded: () => void;
  onRecorded: (decision: RecommendationDecision) => void;
}) {
  const live = recommendation ?? unavailableUntilImpact();
  const pending = useRef(false);
  const [selectedResponse, setSelectedResponse] = useState<RecommendationCode | ''>('');
  const [submitting, setSubmitting] = useState<RecommendationDecisionOutcome | null>(null);
  const [blocked, setBlocked] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function record(request: Parameters<typeof recordRecommendationDecision>[2]) {
    if (pending.current) return;
    pending.current = true;
    setSubmitting(request.outcome);
    setError(null);
    try {
      const recorded = await recordRecommendationDecision(token, shipmentId, request);
      onRecorded(recorded);
      setSelectedResponse('');
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && (reason.status === 403 || reason.status === 409)) {
        setBlocked(true);
      }
      setError(reason instanceof ApiError ? reason.message : UNREACHABLE);
    } finally {
      pending.current = false;
      setSubmitting(null);
    }
  }

  function onModify(event: FormEvent) {
    event.preventDefault();
    if (selectedResponse === '') return;
    void record({ outcome: 'MODIFIED', selectedResponse });
  }

  return (
    <article className="recommendation-decision" aria-label="Recommendation">
      <p className="eyebrow">RECOMMENDATION</p>
      {live.available
        ? (
          <>
            <h3>{live.response.label}</h3>
            <p className="intro">{live.response.wording}</p>
          </>
        )
        : (
          <>
            <h3>Unavailable</h3>
            <p className="intro">{live.unavailable}</p>
          </>
        )}
      {decision ? <CurrentDecision decision={decision} /> : null}
      {live.available && !archived && !blocked
        ? (
          <div className="recommendation-decision-controls">
            <div className="recommendation-decision-actions">
              <button type="button" className="primary-button" disabled={submitting !== null}
                onClick={() => { void record({ outcome: 'APPROVED' }); }}>
                {submitting === 'APPROVED' ? 'Approving...' : 'Approve'}
              </button>
              <button type="button" className="quiet-button" disabled={submitting !== null}
                onClick={() => { void record({ outcome: 'DISMISSED' }); }}>
                {submitting === 'DISMISSED' ? 'Dismissing...' : 'Dismiss'}
              </button>
            </div>
            <form className="recommendation-modify" onSubmit={onModify}>
              <label htmlFor="recommendation-modify-response">Modify with a different response</label>
              <select id="recommendation-modify-response" value={selectedResponse} disabled={submitting !== null}
                onChange={event => setSelectedResponse(event.target.value as RecommendationCode | '')}>
                <option value="">Choose a different response</option>
                {otherResponses(live.response.code).map(code => (
                  <option key={code} value={code}>{RECOMMENDATION_RESPONSES[code].label}</option>
                ))}
              </select>
              <button type="submit" className="secondary-button" disabled={submitting !== null || selectedResponse === ''}>
                {submitting === 'MODIFIED' ? 'Modifying...' : 'Modify'}
              </button>
            </form>
          </div>
        )
        : null}
      {error ? <p className="error-notice" role="alert">{error}</p> : null}
    </article>
  );
}

function CurrentDecision({ decision }: { decision: RecommendationDecision }) {
  return (
    <section className="current-decision" aria-label="Current decision">
      <p className="current-decision-outcome">{outcomeCopy(decision.outcome)}</p>
      <dl>
        <div>
          <dt>Recommendation at the time</dt>
          <dd>
            <strong>{decision.recommendationName}</strong>
            <span>{decision.recommendationWording}</span>
          </dd>
        </div>
        {decision.outcome === 'MODIFIED'
          ? (
            <div>
              <dt>Chosen response</dt>
              <dd>
                <strong>{decision.selectedResponseName}</strong>
                <span>{decision.selectedResponseWording}</span>
              </dd>
            </div>
          )
          : null}
        <div><dt>Decided by</dt><dd>{decision.decidedByName}</dd></div>
        <div>
          <dt>Decided at</dt>
          <dd><time dateTime={decision.decidedAt}>{formatWhen(decision.decidedAt)}</time></dd>
        </div>
      </dl>
    </section>
  );
}

function outcomeCopy(outcome: RecommendationDecisionOutcome) {
  switch (outcome) {
    case 'APPROVED':
      return 'Approved';
    case 'MODIFIED':
      return 'Modified';
    case 'DISMISSED':
      return 'The recommendation was set aside';
    default: {
      const unexpected: never = outcome;
      return unexpected;
    }
  }
}

function otherResponses(recommended: RecommendationCode) {
  return RECOMMENDATION_CODES.filter(code => code !== recommended);
}

function unavailableUntilImpact(): Extract<ShipmentRecommendation, { available: false }> {
  return {
    available: false,
    unavailable: IMPACT_NOT_RETURNED,
    response: null,
    riskLevel: null,
    riskExplanation: null,
    impact: { outcome: 'UNAVAILABLE', detail: 'The impact assessment has not been returned.' },
  };
}
