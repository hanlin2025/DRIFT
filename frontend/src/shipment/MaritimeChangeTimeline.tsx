import { type MaritimeChangeEvent, type MaritimeChangeEvents, type ShipmentRisk } from './api';
import { RiskBadge } from './RiskBadge';
import { formatWhen } from './ShipmentForm';

function eventLabel(type: MaritimeChangeEvent['sourceType']) {
  switch (type) {
    case 'MPA_ETA_REVISION': return 'MPA arrival estimate revision';
    case 'MPA_OFFICIAL_NOTICE': return 'MPA port notice';
    case 'NEA_WEATHER': return 'NEA weather update';
  }
}

function SourceMode({ mode }: { mode: MaritimeChangeEvent['sourceMode'] }) {
  return <span className={`maritime-change-mode maritime-change-mode-${mode.toLowerCase().replaceAll('_', '-')}`}>{mode}</span>;
}

export function MaritimeChangeTimeline({ result, loading, loadingOlder, error, onRefresh, onLoadOlder, risk }: {
  result: MaritimeChangeEvents | null;
  loading: boolean;
  loadingOlder: boolean;
  error: string | null;
  onRefresh: () => void;
  onLoadOlder: (cursor: string) => void;
  risk: ShipmentRisk | null;
}) {
  const events = result?.events.slice().sort((left, right) =>
    Date.parse(right.detectedAt) - Date.parse(left.detectedAt));
  const nextCursor = result?.nextCursor;

  return <section className="shipment-record maritime-change-timeline" aria-label="Maritime change timeline">
    <div className="maritime-change-heading">
      <div>
        <p className="eyebrow">SOURCE CHANGE HISTORY</p>
        <h3>Maritime change timeline</h3>
      </div>
      <button type="button" className="secondary-button" onClick={onRefresh} disabled={loading || loadingOlder}>
        {loading ? 'Refreshing…' : 'Refresh changes'}
      </button>
    </div>
    {result && <p className="maritime-change-source-status">Source availability <strong>{result.availability}</strong></p>}
    {error && <p className="refresh-note" role="status">{error}</p>}
    {!result && loading && <p role="status">Loading maritime changes…</p>}
    {result?.availability === 'UNAVAILABLE' && !error && <p className="refresh-note">Change information is unavailable. No source observation is implied.</p>}
    {!!result?.droppedEventCount && <p className="maritime-change-caveat" role="status">Some source changes on this page had invalid fields and were omitted.</p>}
    {result && result.events.length === 0 && result.availability !== 'UNAVAILABLE' && !result.droppedEventCount && !nextCursor && <p>No detected maritime source changes are available for this shipment.</p>}
    {result && result.events.length === 0 && result.availability !== 'UNAVAILABLE' && !result.droppedEventCount && nextCursor && <p>No changes on this page. Older history is available below.</p>}
    {events && events.length > 0 && <ol className="maritime-change-list">
      {events.map(event => <li key={event.id}>
        <article className="maritime-change-event">
          <div className="maritime-change-event-heading">
            <h4>{eventLabel(event.sourceType)}</h4>
            <SourceMode mode={event.sourceMode} />
          </div>
          <p><strong>Source:</strong> {event.sourceIdentity}</p>
          <p><strong>Change:</strong> {event.eventType === 'NEW' ? 'New source observation' : 'Source observation revised'}</p>
          <dl>
            <div><dt>Previous value</dt><dd>{event.previousValue || 'Not recorded'}</dd></div>
            <div><dt>Current value</dt><dd>{event.currentValue || 'Not recorded'}</dd></div>
            <div><dt>Source event time</dt><dd>{event.sourceEventTime ? formatWhen(event.sourceEventTime) : 'Not recorded'}</dd></div>
            <div><dt>Observed at source</dt><dd>{event.observedAt ? formatWhen(event.observedAt) : 'Not recorded'}</dd></div>
            <div><dt>Retrieved by DRIFT</dt><dd>{formatWhen(event.retrievedAt)}</dd></div>
            <div><dt>Detected by DRIFT</dt><dd>{formatWhen(event.detectedAt)}</dd></div>
            <div><dt>Freshness</dt><dd>{event.freshness}</dd></div>
            <div><dt>Association confidence</dt><dd>{event.associationConfidence}</dd></div>
          </dl>
          <p><strong>Compatibility:</strong> activity {event.compatibility.activity ?? 'UNKNOWN'}; location {event.compatibility.location ?? 'UNKNOWN'}; time {event.compatibility.time ?? 'UNKNOWN'}.</p>
          <p><strong>Provenance:</strong> {event.provenance}</p>
          {event.sourceMode === 'HISTORICAL_BACKFILL' && <p className="maritime-change-caveat">Historical backfill; this record is not a newly observed live change.</p>}
          {event.requiresHumanReview && <p className="maritime-change-review" role="note"><strong>Human review needed.</strong> Confirm this source and shipment association before changing an operational plan.</p>}
          {event.sourceType === 'NEA_WEATHER' && <p className="maritime-change-caveat">Regional weather context does not establish a shipment-specific cause.</p>}
          {event.sourceType === 'MPA_OFFICIAL_NOTICE' && <p className="maritime-change-caveat">A port notice does not by itself establish a vessel visit or berth event.</p>}
        </article>
      </li>)}
    </ol>}
    {nextCursor && <button type="button" className="secondary-button maritime-change-load-older" onClick={() => onLoadOlder(nextCursor)} disabled={loading || loadingOlder}>
      {loadingOlder ? 'Loading older changes…' : 'Load older changes'}
    </button>}
    <div className="maritime-change-risk" aria-label="Current backend risk assessment">
      <strong>Current shipment risk:</strong> {risk ? <><RiskBadge risk={risk} /> {risk.explanation}</> : <RiskBadge risk={null} />}
      <p>This risk is calculated by DRIFT’s existing shipment assessment. A source event is not shown as its cause unless that relationship is explicitly supplied by the backend.</p>
    </div>
    <a className="maritime-change-ai-link" href="#maritime-ai-assessment">Review AI assessment and request an explanation</a>
  </section>;
}
