import type { TrackVessel, TrackWindow, VesselTrack } from './api';
import { formatWhen } from './ShipmentForm';
import { formatHeading, formatSpeed, hasEnoughHistory, INSUFFICIENT_HISTORY, slowdownCount, TRACK_WINDOWS } from './trackSegments';

export const TRACK_LOAD_FAILED = 'Track history could not be loaded. Try Refresh.';
export const SELECT_POINT = 'Select a point on the track to see its time, speed and heading.';

const VESSELS: { id: TrackVessel; label: string }[] = [
  { id: 'MOTHER', label: 'Mother vessel' },
  { id: 'FEEDER', label: 'Feeder vessel' },
];

export function TrackHistory({ vessel, hours, history, loading, failed, feederAssigned, selectedAt, onVessel, onHours, onSelectPoint }: {
  vessel: TrackVessel;
  hours: TrackWindow;
  history: VesselTrack | null;
  loading: boolean;
  failed: boolean;
  feederAssigned: boolean;
  selectedAt: string | null;
  onVessel: (vessel: TrackVessel) => void;
  onHours: (hours: TrackWindow) => void;
  onSelectPoint: (ingestedAt: string) => void;
}) {
  const points = history?.points ?? [];
  const threshold = history?.slowdownThresholdKnots ?? 3;
  const selected = selectedAt == null ? null : points.find(point => point.ingestedAt === selectedAt) ?? null;
  const enough = hasEnoughHistory(points);
  const slowdowns = slowdownCount(points, threshold);

  return (
    <section className="track-history" aria-label="Track history">
      <div className="track-controls">
        <div className="track-toggle" role="group" aria-label="Vessel">
          {VESSELS.map(option => (
            <button key={option.id} type="button" aria-pressed={vessel === option.id}
              disabled={option.id === 'FEEDER' && !feederAssigned}
              onClick={() => onVessel(option.id)}>{option.label}</button>
          ))}
        </div>
        <div className="track-toggle" role="group" aria-label="Time window">
          {TRACK_WINDOWS.map(option => (
            <button key={option} type="button" aria-pressed={hours === option} onClick={() => onHours(option)}>
              {option}h
            </button>
          ))}
        </div>
      </div>
      <p className="track-legend">
        <span className="track-swatch" aria-hidden="true" /> Track
        <span className="track-swatch slow" aria-hidden="true" /> Below {threshold} kn (slowdown)
      </p>
      {failed && <p className="track-note" role="status">{TRACK_LOAD_FAILED}</p>}
      {loading && !history && <p className="track-note" role="status">Loading track history...</p>}
      {history && !enough && <p className="track-empty" role="status">{INSUFFICIENT_HISTORY}</p>}
      {history && enough && (
        <>
          <p className="track-summary">
            {points.length} positions in the last {history.windowHours} hours
            {slowdowns > 0 ? ` · ${slowdowns} slowdown ${slowdowns === 1 ? 'segment' : 'segments'}` : ' · no slowdowns'}
          </p>
          {history.truncated && <p className="track-note">Showing the latest {history.pointLimit} positions in this window.</p>}
          {selected
            ? (
              <dl className="track-point" aria-label="Selected track point">
                <div><dt>Time</dt><dd><time dateTime={selected.ingestedAt}>{formatWhen(selected.ingestedAt)}</time></dd></div>
                <div><dt>Speed</dt><dd>{formatSpeed(selected.speedOverGroundKnots)}</dd></div>
                <div><dt>Heading</dt><dd>{formatHeading(selected.trueHeadingDegrees)}</dd></div>
              </dl>
            )
            : <p className="track-hint">{SELECT_POINT}</p>}
          <details className="track-list">
            <summary>All positions</summary>
            <ol aria-label="Track points">
              {points.map((point, index) => (
                <li key={`${point.ingestedAt}-${index}`}>
                  <button type="button" aria-pressed={selectedAt === point.ingestedAt} onClick={() => onSelectPoint(point.ingestedAt)}>
                    {formatWhen(point.ingestedAt)} · {formatSpeed(point.speedOverGroundKnots)}
                  </button>
                </li>
              ))}
            </ol>
          </details>
        </>
      )}
    </section>
  );
}
