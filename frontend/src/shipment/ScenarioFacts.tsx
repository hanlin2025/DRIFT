import { MaritimeContextPanel } from './MaritimeContextPanel';
import { RiskBadge } from './RiskBadge';
import type { MaritimeContext } from './api';
import type { DecodedScenarioSnapshot } from './scenarioDomain';

function contextForPanel(context: DecodedScenarioSnapshot['maritimeContext']): MaritimeContext {
  return {
    ...context,
    evidence: context.evidence.map(item => ({ ...item })),
    notices: context.notices.map(item => ({
      ...item,
      activityEvidence: [...item.activityEvidence],
      locationEvidence: [...item.locationEvidence],
    })),
  };
}

function utcFact(label: string, value: string | null) {
  return <div><dt>{label}</dt><dd>{utcValue(value)}</dd></div>;
}

function utcValue(value: string | null) {
  return value ? <time dateTime={value}>{value}</time> : 'Not recorded';
}

/** Read-only rendering of calculations and evidence already validated in one scenario snapshot. */
export function ScenarioFacts({ snapshot }: { snapshot: DecodedScenarioSnapshot }) {
  const facts = snapshot.selectedShipment;
  const mother = snapshot.aisObservations.find(row => row.vesselRole === 'MOTHER');
  const feeder = snapshot.aisObservations.find(row => row.vesselRole === 'FEEDER');
  const arrival = facts.arrivalEstimate;

  return (
    <>
      <section className="shipment-record scenario-facts" aria-label="Scenario snapshot facts">
        <p className="eyebrow">SYNTHETIC SCENARIO · {snapshot.stage}</p>
        <p>This is a controlled fictional demonstration. It does not change the saved shipment or trigger an operational decision.</p>
        <dl>
          <div><dt>Stage</dt><dd>{snapshot.stageOrder + 1} of {snapshot.stageCount} · {snapshot.stage}</dd></div>
          {utcFact('Simulation reference cutoff (UTC)', snapshot.simulationCutoffUtc)}
          {utcFact('Scenario map playback cutoff (UTC)', snapshot.mapPlaybackCutoffUtc)}
          <div><dt>Fictional scenario shipment</dt><dd>{facts.scenarioShipmentReference}</dd></div>
          <div><dt>Planned origin</dt><dd className="is-missing">Not recorded</dd></div>
          <div><dt>Planned destination</dt><dd className="is-missing">Not recorded</dd></div>
          <div><dt>Scenario transshipment port</dt><dd>{facts.transshipmentPort}</dd></div>
          {utcFact('Planned mother-vessel arrival (UTC)', facts.plannedMotherArrivalAt)}
          {utcFact('Planned feeder-vessel departure (UTC)', facts.plannedFeederDepartureAt)}
          <div><dt>Planned connection window</dt><dd>{facts.plannedConnectionWindow.status === 'AVAILABLE'
            ? `${facts.plannedConnectionWindow.window.duration} (${facts.plannedConnectionWindow.window.totalSeconds} seconds)`
            : 'Unavailable'}</dd></div>
          <div><dt>Remaining connection window</dt><dd>{facts.remainingConnectionWindow.status === 'AVAILABLE'
            ? `${facts.remainingConnectionWindow.duration ?? 'Available'} (${facts.remainingConnectionWindow.totalSeconds ?? 'Not recorded'} seconds)`
            : facts.remainingConnectionWindow.status}</dd></div>
          <div><dt>Mother-vessel arrival estimate</dt><dd>
            {arrival.estimatedArrivalAt
              ? <time dateTime={arrival.estimatedArrivalAt}>{arrival.estimatedArrivalAt}</time>
              : arrival.unavailableReason?.message ?? 'Unavailable'}
            {arrival.difference && <p>{arrival.difference}</p>}
            {arrival.limitation && <p>{arrival.limitation}</p>}
          </dd></div>
          <div><dt>Scenario risk</dt><dd><RiskBadge risk={facts.risk} /> {facts.risk.explanation}</dd></div>
          <div><dt>Scenario deadline assessment</dt><dd>
            <strong>{facts.businessImpact.outcome?.replaceAll('_', ' ') ?? 'Unavailable'}</strong>
            <p>{facts.businessImpact.message}</p>
            <p>Predicted arrival (UTC): {utcValue(facts.businessImpact.estimatedArrivalAt)}</p>
            <p>Scenario deadline (UTC): {utcValue(facts.businessImpact.deadline)}</p>
            <p>Recorded port: {facts.businessImpact.recordedTransshipmentPort ?? 'Not recorded'}</p>
          </dd></div>
          {mother && <>
            {utcFact('Mother observation time (UTC)', mother.observedAtUtc)}
            {utcFact('Mother receipt time (UTC)', mother.receivedAtUtc)}
            {utcFact('Mother position ingestion time (UTC)', mother.position.ingestedAt)}
          </>}
          {feeder && <>
            {utcFact('Feeder observation time (UTC)', feeder.observedAtUtc)}
            {utcFact('Feeder receipt time (UTC)', feeder.receivedAtUtc)}
            {utcFact('Feeder position ingestion time (UTC)', feeder.position.ingestedAt)}
          </>}
        </dl>
      </section>
      <MaritimeContextPanel context={contextForPanel(snapshot.maritimeContext)} unavailable={false} />
    </>
  );
}
