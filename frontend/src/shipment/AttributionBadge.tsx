import { type AttributionCause, type AttributionSource, type DelayAttribution } from './api';
import { RISK_LABELS } from './RiskBadge';

export const CAUSE_LABELS: Record<AttributionCause, string> = {
  NONE_DETECTED: 'No delay cause',
  VESSEL_BEHIND_SCHEDULE: 'Vessel behind',
  PORT_CONDITION: 'Port',
  WEATHER: 'Weather',
  MULTIPLE: 'Port + weather',
  UNAVAILABLE: 'No signals',
};

export const SOURCE_LABELS: Record<AttributionSource, string> = {
  AIS: 'AIS',
  MPA_ETA_REVISION: 'MPA arrival',
  MPA_OFFICIAL_NOTICE: 'MPA notice',
  NEA_WEATHER: 'NEA weather',
};

/** The likely cause of the shipment's behaviour. List and detail share it; no badge when nothing could be read. */
export function AttributionBadge({ cause }: { cause: AttributionCause | null | undefined }) {
  if (!cause || cause === 'UNAVAILABLE' || cause === 'NONE_DETECTED') return null;
  return <span className={`cause-badge cause-${cause.toLowerCase().replace(/_/g, '-')}`}>{CAUSE_LABELS[cause]}</span>;
}

/** The "Likely cause" fact on the shipment detail: the cause, the signals that were read, and any raised risk. */
export function AttributionFact({ attribution }: { attribution: DelayAttribution | null | undefined }) {
  if (!attribution) return null;
  return (
    <div>
      <dt>Likely cause</dt>
      <dd>
        <AttributionBadge cause={attribution.cause} /> {attribution.summary}
        {attribution.riskRaisedFrom
          ? <p className="cause-raised">Risk raised from {RISK_LABELS[attribution.riskRaisedFrom]} by external port data.</p>
          : null}
        {attribution.signals.length > 0
          ? (
            <ul className="cause-signals">
              {attribution.signals.map((signal, index) => (
                <li key={`${signal.source}-${index}`} className={signal.supportsDelay ? 'supports-delay' : undefined}>
                  <span className="cause-source">{SOURCE_LABELS[signal.source]}</span> {signal.summary}
                  {signal.observedAt ? <span className="cause-observed"> · {signal.observedAt}</span> : null}
                </li>
              ))}
            </ul>
          )
          : null}
      </dd>
    </div>
  );
}
