import { type MaritimeContext } from './api';
import { formatWhen } from './ShipmentForm';

export function MaritimeContextPanel({ context, unavailable }: {
  context: MaritimeContext | null;
  unavailable: boolean;
}) {
  if (unavailable) return <section className="notice" aria-label="Maritime Context">Maritime context is unavailable. Shipment details are still up to date.</section>;
  if (!context || !context.enabled) return <section className="notice" aria-label="Maritime Context">Maritime context is not enabled for this environment.</section>;
  if (context.evidence.length === 0) return <section className="notice" aria-label="Maritime Context">No point-in-time maritime evidence is available at this assessment cutoff. {context.limitation}</section>;
  return <section className="shipment-record maritime-context" aria-label="Maritime Context">
    <p className="eyebrow">MARITIME CONTEXT</p>
    <p className="refresh-note">Assessment cutoff: {formatWhen(context.assessmentCutoffUtc)}. Retrieval times, not future source events, determine whether replay evidence is included.</p>
    <p>{context.limitation}</p>
    {context.evidence.map(item => <article key={item.evidenceId}>
      <h3>{item.title}</h3>
      <p><strong>Source:</strong> {item.sourceType} · {item.sourceMode} · <strong>Association confidence:</strong> {item.shipmentAssociationConfidence}</p>
      <p><strong>Compatibility:</strong> activity {item.activityCompatibility}; location {item.locationCompatibility}; time {item.timeCompatibility}; overall {item.compatibilityState}.</p>
      {item.changeMagnitude && <p><strong>Observed change:</strong> {item.previousValue || 'not recorded'} → {item.currentValue || 'not recorded'} ({item.changeMagnitude})</p>}
      <p><strong>Provenance:</strong> {item.sourceIdentifier}. {item.citation || 'No citation is available.'}</p>
      {(item.sourceEventAt || item.observedAt || item.retrievedAtUtc) && <p>Source event {item.sourceEventAt ? formatWhen(item.sourceEventAt) : 'not recorded'}; observed {item.observedAt ? formatWhen(item.observedAt) : 'not recorded'}; retrieved {item.retrievedAtUtc ? formatWhen(item.retrievedAtUtc) : 'not recorded'}.</p>}
      {item.sourceUrl && <p><a href={item.sourceUrl} target="_blank" rel="noreferrer">Source link</a></p>}
      <p>{item.limitation}</p>
      {item.humanReviewRequired && <p className="refresh-note">Human review required. This evidence does not change the shipment, ETA, risk, or alerts.</p>}
    </article>)}
  </section>;
}
