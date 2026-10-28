import { type MaritimeContext } from './api';
import { formatWhen } from './ShipmentForm';

export function MaritimeContextPanel({ context, unavailable }: {
  context: MaritimeContext | null;
  unavailable: boolean;
}) {
  if (unavailable) return <section className="notice" aria-label="Maritime Context">Maritime context is unavailable. Shipment details are still up to date.</section>;
  if (!context || !context.enabled) return <section className="notice" aria-label="Maritime Context">Maritime context is not enabled for this environment.</section>;
  if (context.notices.length === 0) return <section className="notice" aria-label="Maritime Context">No potentially relevant maritime notices are available. {context.limitation}</section>;
  return <section className="shipment-record maritime-context" aria-label="Maritime Context">
    <p className="eyebrow">MARITIME CONTEXT</p>
    {context.sourceMode === 'SYNTHETIC_DEMONSTRATION' && <p className="refresh-note" role="status">Demonstration data only — not live maritime intelligence.</p>}
    <p>{context.limitation}</p>
    {context.notices.filter(notice => notice.compatibilityState !== 'INCOMPATIBLE').map(notice => <article key={notice.noticeId}>
      <h3>{notice.title}</h3>
      <p><strong>Compatibility:</strong> {notice.compatibilityState} · <strong>Association confidence:</strong> {notice.shipmentAssociationConfidence}</p>
      <p>{notice.limitation}</p>
      <p><a href={notice.officialSourceUrl} target="_blank" rel="noreferrer">Official source</a> · {notice.citation}</p>
      {(notice.publishedAt || notice.observedAt) && <p>Published {notice.publishedAt ? formatWhen(notice.publishedAt) : 'not recorded'}; observed {notice.observedAt ? formatWhen(notice.observedAt) : 'not recorded'}.</p>}
      <p>Effective {notice.effectiveFrom ?? 'not recorded'} to {notice.effectiveUntil ?? 'not recorded'} · {notice.supersessionStatus}</p>
      {notice.humanReviewRequired && <p className="refresh-note">Human review required before acting on this candidate notice.</p>}
    </article>)}
  </section>;
}
