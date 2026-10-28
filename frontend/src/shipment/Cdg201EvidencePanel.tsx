import type { Cdg201ArrivalEvidence } from './api';
import { formatWhen } from './ShipmentForm';

export function Cdg201EvidencePanel({ evidence }: { evidence: Cdg201ArrivalEvidence | null }) {
  if (evidence === null) return null;
  const scenario = evidence.simulatedScenario;
  return (
    <article className="shipment-record cdg201-evidence" aria-label="CDG-201 external arrival evidence">
      <p className="eyebrow">CDG-201 · READ-ONLY REPLAY</p>
      <h3>Observed MPA expected-arrival revision</h3>
      <p className="intro">Recorded evidence, not an actual arrival or a verified shipment connection.</p>
      <dl>
        <div><dt>Vessel</dt><dd>{evidence.vesselName} · IMO {evidence.vesselImo}</dd></div>
        <div><dt>Expected arrival</dt><dd><code>{evidence.previousExpectedArrival}</code> → <code>{evidence.revisedExpectedArrival}</code></dd></div>
        <div><dt>Observed ETA revision</dt><dd>+{evidence.revisionMinutes} minutes</dd></div>
        <div><dt>Prior source snapshot</dt><dd><time dateTime={evidence.previousCollectionUtc}>{formatWhen(evidence.previousCollectionUtc)}</time> · {evidence.previousCollectionRunId}</dd></div>
        <div><dt>Revised source snapshot</dt><dd><time dateTime={evidence.latestCollectionUtc}>{formatWhen(evidence.latestCollectionUtc)}</time> · {evidence.latestCollectionRunId}</dd></div>
        <div><dt>Shipment association</dt><dd>{evidence.shipmentAssociation.replaceAll('_', ' ')}</dd></div>
        <div><dt>MPA location mapping</dt><dd>{evidence.locationMappingStatus.replaceAll('_', ' ')}</dd></div>
        <div><dt>Prior evidence</dt><dd><code>{evidence.previousSourceManifest}</code><br /><code>{evidence.previousSourceRawFile}</code></dd></div>
        <div><dt>Revised evidence</dt><dd><code>{evidence.sourceManifest}</code><br /><code>{evidence.sourceRawFile}</code></dd></div>
      </dl>
      <p className="cdg201-limitation"><strong>Limitation:</strong> {evidence.sourceTimestampSemantics} {evidence.limitations}</p>
      <section className="cdg201-scenario" aria-label="Simulated connection scenario">
        <p className="eyebrow">SIMULATED CONNECTION SCENARIO</p>
        <p><strong>Not the production shipment risk.</strong> Margin: {scenario.originalConnectionMarginMinutes} → {scenario.revisedConnectionMarginMinutes} minutes (−{scenario.reductionMinutes} minutes).</p>
        <p>Illustrative classification: {scenario.simulatedRiskBefore} → {scenario.simulatedRiskAfter}.</p>
        <p>{scenario.disclaimer}</p>
      </section>
    </article>
  );
}
