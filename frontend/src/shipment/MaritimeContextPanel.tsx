import { type MaritimeContext, type MaritimeEvidence } from './api';
import { reassessmentCue } from './reassessmentCue';
import { formatWhen } from './ShipmentForm';

function operationalRelevance(item: MaritimeEvidence) {
  if (item.compatibilityState === 'INCOMPATIBLE') return 'This item is not used in the current assessment because it does not match the shipment context.';
  if (item.sourceMode === 'UNAVAILABLE') return 'This information is not currently available for the assessment.';
  if (item.sourceType === 'MPA_ETA_REVISION') return 'The updated arrival expectation should be considered alongside the shipment plan.';
  if (item.sourceType === 'MPA_OFFICIAL_NOTICE') return 'This port information is relevant context for planning; it does not establish a shipment impact.';
  return 'This regional weather observation provides planning context; it does not establish a vessel or shipment impact.';
}

function operationalTitle(item: MaritimeEvidence) {
  if (item.sourceMode === 'UNAVAILABLE') return 'Weather information';
  if (item.sourceType === 'MPA_ETA_REVISION') return 'Arrival schedule update';
  if (item.sourceType === 'MPA_OFFICIAL_NOTICE') return 'Port operating notice';
  return 'Weather conditions';
}

export function MaritimeContextPanel({ context, unavailable }: {
  context: MaritimeContext | null;
  unavailable: boolean;
}) {
  if (unavailable) return <section className="notice" aria-label="Maritime Context">Maritime context is unavailable. Shipment details are still up to date.</section>;
  const cue = reassessmentCue(context);
  if (cue.state === 'denied') {
    return <section className="notice" aria-label="Maritime Context">
      <p>Maritime context is not enabled for this environment.</p>
      <p>{cue.detail}</p>
    </section>;
  }
  if (!context || cue.state === 'empty') {
    return <section className="notice" aria-label="Maritime Context">
      <p>No point-in-time maritime evidence is available at this assessment cutoff. {context?.limitation}</p>
      <p>{cue.detail}</p>
    </section>;
  }
  return <section className="shipment-record maritime-context" aria-label="Maritime Context">
    <p className="eyebrow">MARITIME CONTEXT</p>
    <dl>
      <div><dt>Reassessment</dt><dd>{cue.headline}</dd></div>
      <div><dt>Validated result</dt><dd>None</dd></div>
      <div><dt>Generation time</dt><dd>Not recorded</dd></div>
      <div><dt>Evidence cutoff</dt><dd><time dateTime={context.assessmentCutoffUtc}>{formatWhen(context.assessmentCutoffUtc)}</time></dd></div>
    </dl>
    <p className="refresh-note">{cue.detail}</p>
    <p className="refresh-note">Evidence cutoff: {formatWhen(context.assessmentCutoffUtc)}. Retrieval times, not future source events, determine whether replay evidence is included.</p>
    <p>{context.limitation}</p>
    <p>Relevant vessel, port, and weather information for the current shipment review.</p>
    {context.evidence.map(item => <article key={item.evidenceId}>
      <h3>{operationalTitle(item)}</h3>
      {item.changeMagnitude && <p><strong>What changed:</strong> {item.previousValue || 'Not recorded'} → {item.currentValue || 'Not recorded'} ({item.changeMagnitude})</p>}
      <p><strong>Why it matters:</strong> {operationalRelevance(item)}</p>
      <p><strong>Uncertainty:</strong> {item.limitation}</p>
      {item.humanReviewRequired && <p><strong>Recommended next checks:</strong> Review this information alongside current vessel and port operations before changing any plan.</p>}
    </article>)}
    <details>
      <summary>Source details</summary>
      {context.evidence.map(item => <EvidenceItem key={item.evidenceId} item={item} />)}
    </details>
  </section>;
}

function EvidenceItem({ item }: { item: MaritimeEvidence }) {
  return <article>
    <h3>{item.title}</h3>
    <p><strong>Source:</strong> {item.sourceType} · {item.sourceMode} · <strong>Freshness:</strong> {item.freshness} · <strong>Association confidence:</strong> {item.shipmentAssociationConfidence}</p>
    <p><strong>Compatibility:</strong> activity {item.activityCompatibility}; location {item.locationCompatibility}; time {item.timeCompatibility}; overall {item.compatibilityState}.</p>
    {item.changeMagnitude && <p><strong>Observed change:</strong> {item.previousValue || 'not recorded'} → {item.currentValue || 'not recorded'} ({item.changeMagnitude})</p>}
    <p><strong>Provenance:</strong> {item.sourceIdentifier}. {item.citation || 'No citation is available.'}</p>
    {(item.sourceEventAt || item.observedAt || item.retrievedAtUtc) && <p>Source event {item.sourceEventAt ? formatWhen(item.sourceEventAt) : 'not recorded'}; observed {item.observedAt ? formatWhen(item.observedAt) : 'not recorded'}; retrieved {item.retrievedAtUtc ? formatWhen(item.retrievedAtUtc) : 'not recorded'}.</p>}
    {item.sourceUrl && <p><a href={item.sourceUrl} target="_blank" rel="noreferrer">Open source</a></p>}
    <p>{item.limitation}</p>
    {item.sourceMode === 'SYNTHETIC_DEMONSTRATION' && <p>This is fictional scenario evidence. It is not an authentic maritime observation and does not independently verify cargo carriage, a real vessel visit, or shipment impact.</p>}
    {item.sourceMode === 'UNAVAILABLE' && <p className="refresh-note">This source failed. It is not a validated result. A source-availability status is not an observed weather condition.</p>}
    {item.shipmentAssociationConfidence === 'UNVERIFIED_IMO_ONLY' && <p className="refresh-note">An IMO match is not a verified visit.</p>}
    {item.sourceType === 'NEA_WEATHER' && <p className="refresh-note">Regional weather is not a demonstrated cause for this shipment.</p>}
    {item.sourceType === 'MPA_OFFICIAL_NOTICE' && <p className="refresh-note">A general notice is not a specific berth event.</p>}
    {item.compatibilityState === 'INCOMPATIBLE' && <p className="refresh-note">This item was excluded from the AI assessment because it is incompatible with the shipment context.</p>}
    {item.humanReviewRequired && <p className="refresh-note">Human review required. This evidence does not change the shipment, ETA, risk, or alerts.</p>}
  </article>;
}
