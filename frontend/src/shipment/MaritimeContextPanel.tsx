import { type MaritimeContext } from './api';
import { formatWhen } from './ShipmentForm';

function operationalRelevance(item: MaritimeContext['evidence'][number]) {
  if (item.compatibilityState === 'INCOMPATIBLE') return 'This item is not used in the current assessment because it does not match the shipment context.';
  if (item.sourceMode === 'UNAVAILABLE') return 'This information is not currently available for the assessment.';
  if (item.sourceType === 'MPA_ETA_REVISION') return 'The updated arrival expectation should be considered alongside the shipment plan.';
  if (item.sourceType === 'MPA_OFFICIAL_NOTICE') return 'This port information is relevant context for planning; it does not establish a shipment impact.';
  return 'This regional weather observation provides planning context; it does not establish a vessel or shipment impact.';
}

function operationalTitle(item: MaritimeContext['evidence'][number]) {
  if (item.sourceMode === 'UNAVAILABLE') return 'Weather information';
  if (item.sourceType === 'MPA_ETA_REVISION') return 'Arrival schedule update';
  if (item.sourceType === 'MPA_OFFICIAL_NOTICE') return 'Port operating notice';
  return 'Weather conditions';
}

function evidenceTiming(item: MaritimeContext['evidence'][number]) {
  const values = [
    item.sourceEventAt && `source event ${formatWhen(item.sourceEventAt)}`,
    item.observedAt && `observed ${formatWhen(item.observedAt)}`,
    item.retrievedAtUtc && `retrieved ${formatWhen(item.retrievedAtUtc)}`,
  ].filter(Boolean);
  return values.length ? values.join(' · ') : 'Source-event and observation times were not supplied.';
}

export function MaritimeContextPanel({ context, unavailable }: {
  context: MaritimeContext | null;
  unavailable: boolean;
}) {
  if (unavailable) return <section className="notice" aria-label="Maritime Context">Maritime context is unavailable. Shipment details are still up to date.</section>;
  if (!context || !context.enabled) return <section className="notice" aria-label="Maritime Context">Maritime context is not enabled for this environment.</section>;
  if (context.evidence.length === 0) return <section className="notice" aria-label="Maritime Context">No point-in-time maritime evidence is available at this assessment cutoff. {context.limitation}</section>;
  return <section className="shipment-record maritime-context" aria-label="Maritime Context">
    <p className="eyebrow">MARITIME CONTEXT</p>
    <p>Relevant vessel, port, and weather information for the current shipment review.</p>
    {context.evidence.map(item => <article key={item.evidenceId}>
      <h3>{operationalTitle(item)}</h3>
      {item.changeMagnitude && <p><strong>What changed</strong>{item.previousValue || 'Not recorded'} → {item.currentValue || 'Not recorded'} ({item.changeMagnitude})</p>}
      <p><strong>Why it matters</strong>{operationalRelevance(item)}</p>
      <p><strong>Uncertainty</strong>{item.limitation}</p>
      {item.humanReviewRequired && <p><strong>Recommended next checks</strong>Review this information alongside current vessel and port operations before changing any plan.</p>}
      <details>
        <summary>Evidence details</summary>
        <p><strong>Source title:</strong> {item.title}</p>
        <p><strong>Source:</strong> {item.sourceIdentifier}. {item.citation || 'No citation is available.'}</p>
        <p><strong>Source status:</strong> {item.sourceMode}. Association: {item.shipmentAssociationConfidence}. Compatibility: activity {item.activityCompatibility}, location {item.locationCompatibility}, time {item.timeCompatibility}, overall {item.compatibilityState}.</p>
        <p><strong>Timing:</strong> {evidenceTiming(item)}</p>
        {item.sourceMode === 'SYNTHETIC_DEMONSTRATION' && <p>This is fictional scenario evidence. It is not an authentic maritime observation and does not independently verify cargo carriage, a real vessel visit, or shipment impact.</p>}
        {item.sourceMode === 'UNAVAILABLE' && <p>This is a source-availability status, not an observed weather condition.</p>}
        {item.compatibilityState === 'INCOMPATIBLE' && <p>This item was excluded from the AI assessment because it is incompatible with the shipment context.</p>}
        {item.sourceUrl && <p><a href={item.sourceUrl} target="_blank" rel="noreferrer">Open source</a></p>}
      </details>
    </article>)}
    <details>
      <summary>Assessment details</summary>
      <p>Assessment cutoff: {formatWhen(context.assessmentCutoffUtc)}. Retrieval time determines whether source information was available for this assessment.</p>
      <p>{context.limitation}</p>
    </details>
  </section>;
}
