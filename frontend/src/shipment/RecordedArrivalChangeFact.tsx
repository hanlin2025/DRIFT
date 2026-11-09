import type { Cdg201ArrivalEvidence, Shipment } from './api';
import { RISK_LABELS } from './RiskBadge';

export function RecordedArrivalChangeFact({ shipment, evidence }: {
  shipment: Shipment;
  evidence: Cdg201ArrivalEvidence | null;
}) {
  if (evidence === null) return null;
  return (
    <div>
      <dt>Recorded arrival change</dt>
      <dd>
        <p>{revisionSentence(evidence)} This recorded revision is not an actual arrival.</p>
        <p>{connectionRiskSentence(shipment)}</p>
      </dd>
    </div>
  );
}

function revisionSentence(evidence: Cdg201ArrivalEvidence) {
  return `${evidence.vesselName}, IMO ${evidence.vesselImo}, has a recorded expected-arrival revision from ${evidence.previousExpectedArrival} to ${evidence.revisedExpectedArrival}, ${revisionPhrase(evidence.revisionMinutes)}.`;
}

function revisionPhrase(minutes: number) {
  const amount = Math.abs(minutes);
  const unit = amount === 1 ? 'minute' : 'minutes';
  if (minutes > 0) return `${amount} ${unit} later`;
  if (minutes < 0) return `${amount} ${unit} earlier`;
  return '0 minutes';
}

function connectionRiskSentence(shipment: Shipment) {
  const risk = shipment.risk;
  if (!risk) return 'The connection risk on this shipment is not assessed.';
  return `The connection risk to act on is the one already on this shipment: ${RISK_LABELS[risk.level]}. ${risk.explanation}`;
}
