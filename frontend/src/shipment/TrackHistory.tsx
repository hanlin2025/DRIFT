import { useEffect, useState } from 'react';
import type { TrackPoint, TrackVessel, TrackWindow, VesselTrack } from './api';
import type { TrackDisplayMode, TrackReadState } from './trackPresentationContract';
import { formatWhen } from './ShipmentForm';
import { formatSpeed, hasEnoughHistory, INSUFFICIENT_HISTORY, slowdownCount, TRACK_WINDOWS } from './trackSegments';
import { formatBearing, NOT_REPORTED } from './VesselPanel';

export const TRACK_LOAD_FAILED = 'Track history could not be loaded. Try Refresh.';
export const SELECT_POINT = 'Select an observation to see its receipt time, position, speed and heading.';

const VESSELS: { id: TrackVessel; label: string }[] = [
  { id: 'MOTHER', label: 'Mother vessel' },
  { id: 'FEEDER', label: 'Feeder vessel' },
];

const DISPLAY_MODES: { id: TrackDisplayMode; label: string }[] = [
  { id: 'live', label: 'Live' },
  { id: 'scenario', label: 'Scenario' },
  { id: 'recorded', label: 'Recorded AIS' },
];

export function TrackHistory({ vessel, hours, history, loading, failed, feederAssigned, selectedId, onVessel, onHours, onSelectPoint,
  mode = 'live', displayModes = ['live', 'recorded'], cutoff = null, playbackPoints, readState, statusMessage,
  scenarioReferenceCutoff = null, sourceShipmentId, mappingVersion, recordedCaption = 'Recorded AIS is a display reference. It does not change shipment ETA, risk or operational decisions.',
  onDisplayModeChange, onCutoffRequest, onPlaybackStep, onRefreshRequested }: {
  vessel: TrackVessel;
  hours: TrackWindow;
  history: VesselTrack | null;
  loading: boolean;
  failed: boolean;
  feederAssigned: boolean;
  selectedId: number | null;
  onVessel: (vessel: TrackVessel) => void;
  onHours: (hours: TrackWindow) => void;
  onSelectPoint: (id: number) => void;
  mode?: TrackDisplayMode;
  displayModes?: TrackDisplayMode[];
  cutoff?: string | null;
  playbackPoints?: TrackPoint[];
  readState?: TrackReadState;
  statusMessage?: string | null;
  scenarioReferenceCutoff?: string | null;
  sourceShipmentId?: string | null;
  mappingVersion?: string | null;
  recordedCaption?: string | null;
  onDisplayModeChange?: (mode: TrackDisplayMode) => void;
  onCutoffRequest?: (cutoff: string) => void;
  onPlaybackStep?: (direction: 'previous' | 'next') => void;
  onRefreshRequested?: () => void;
}) {
  const points = history?.points ?? [];
  const shownPoints = mode === 'recorded' && playbackPoints ? playbackPoints : points;
  const threshold = history?.slowdownThresholdKnots ?? 3;
  const selected = selectedId == null ? null : shownPoints.find(point => point.id === selectedId) ?? null;
  const enough = hasEnoughHistory(points);
  const slowdowns = slowdownCount(points, threshold);
  const [cutoffDraft, setCutoffDraft] = useState(() => toUtcInput(cutoff ?? history?.to ?? null));

  useEffect(() => {
    setCutoffDraft(toUtcInput(cutoff ?? history?.to ?? null));
  }, [cutoff, history?.to, mode]);

  const canStep = mode === 'recorded' && shownPoints.length > 0;
  const cutoffValue = cutoff ?? (mode === 'recorded' ? history?.cutoff ?? history?.to ?? null : history?.cutoff ?? null);
  const contextLabel = history ? trackContextLabel(history, mode) : null;
  const status = history?.status ?? null;

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
        {displayModes.length > 1 && (
          <div className="track-toggle" role="group" aria-label="Position display mode">
            {DISPLAY_MODES.filter(option => displayModes.includes(option.id)).map(option => (
              <button key={option.id} type="button" aria-pressed={mode === option.id}
                onClick={() => onDisplayModeChange?.(option.id)}>{option.label}</button>
            ))}
          </div>
        )}
        {onRefreshRequested && <button type="button" className="refresh-button" onClick={onRefreshRequested}>Refresh positions</button>}
      </div>
      <p className="track-legend">
        <span className="track-swatch" aria-hidden="true" /> Track
        <span className="track-swatch slow" aria-hidden="true" /> Below {threshold} kn (slowdown)
      </p>

      {mode === 'recorded' && (
        <div className="track-playback-controls">
          <label>
            Recorded AIS receipt-time cutoff (UTC)
            <input aria-label="Recorded AIS receipt-time cutoff (UTC)" type="datetime-local" step="1"
              value={cutoffDraft} onChange={event => setCutoffDraft(event.currentTarget.value)} />
          </label>
          <button type="button" className="secondary-button" disabled={!cutoffDraft || loading}
            onClick={() => {
              const value = fromUtcInput(cutoffDraft);
              if (value) onCutoffRequest?.(value);
            }}>Load cutoff</button>
          <button type="button" className="secondary-button" disabled={!canStep || loading}
            onClick={() => onPlaybackStep?.('previous')}>Previous position</button>
          <button type="button" className="secondary-button" disabled={!canStep || loading}
            onClick={() => onPlaybackStep?.('next')}>Next position</button>
        </div>
      )}

      {contextLabel && <p className={`track-context track-context-${history?.context?.toLowerCase() ?? mode}`}>{contextLabel}</p>}
      <p className="track-identity-note">Mother and feeder are independent display candidates; a booking or cargo connection is not confirmed.</p>
      {scenarioReferenceCutoff && mode === 'recorded' && (
        <p className="track-time-reference">Scenario reference: <time dateTime={scenarioReferenceCutoff}>{formatWhen(scenarioReferenceCutoff)}</time>. Recorded AIS cutoff is separate.</p>
      )}
      {cutoffValue && mode === 'recorded' && (
        <p className="track-time-reference">Recorded receipt-time cutoff: <time dateTime={cutoffValue}>{formatWhen(cutoffValue)}</time></p>
      )}
      {readState === 'loading' && !history && <p className="track-note" role="status">Loading track history...</p>}
      {readState === 'unavailable' && <p className="track-note" role="status">{statusMessage ?? 'Recorded track data is unavailable for this vessel and cutoff.'}</p>}
      {readState === 'denied' && <p className="track-note" role="status">{statusMessage ?? 'Track history is unavailable to this account.'}</p>}
      {(failed || readState === 'error') && <p className="track-note" role="status">{statusMessage ?? TRACK_LOAD_FAILED}</p>}
      {loading && history && <p className="track-note" role="status">Loading the requested cutoff…</p>}
      {status === 'STALE' && <p className="track-note" role="status">This track is stale at its requested receipt-time cutoff.</p>}
      {status === 'OFFLINE' && history?.feedStatus === 'OFFLINE' && <p className="track-note" role="status">AIS feed status is offline. Stored track observations remain separate from feed availability.</p>}

      {history && points.length === 0 && (
        <p className="track-empty" role="status">
          No positions were received in this window.
          {history.fallbackUsed && history.lastKnownPosition
            ? <> Last known position: {coordinate(history.lastKnownPosition)} at {formatWhen(history.lastKnownPosition.ingestedAt)}.</>
            : null}
        </p>
      )}
      {history && points.length > 0 && (
        <>
          <p className="track-summary">
            {points.length} positions in the last {history.windowHours} hours
            {slowdowns > 0 ? ` · ${slowdowns} slowdown ${slowdowns === 1 ? 'segment' : 'segments'}` : ' · no slowdowns'}
          </p>
          {!enough && <p className="track-note">{INSUFFICIENT_HISTORY} This observation is shown at its recorded time; no movement is inferred.</p>}
          {history.truncated && <p className="track-note">Showing the latest {history.pointLimit} positions in this window.</p>}
          <p className="track-gap-note">Track lines are omitted across gaps longer than one hour.</p>
          {selected
            ? (
              <dl className="track-point" aria-label="Selected track point">
                <div><dt>Receipt time</dt><dd><time dateTime={selected.ingestedAt}>{formatWhen(selected.ingestedAt)}</time></dd></div>
                <div><dt>Position</dt><dd>{coordinate(selected)}</dd></div>
                <Reading label="Speed" value={formatSpeed(selected.speedOverGroundKnots)} />
                <Reading label="Course" value={formatBearing(selected.courseOverGroundDegrees)} />
                <Reading label="Heading" value={formatBearing(selected.trueHeadingDegrees)} />
                {selected.source && <div><dt>Observation source</dt><dd>{mode === 'recorded' || history?.context === 'RECORDED' ? `Recorded · ${selected.source}` : selected.source}</dd></div>}
              </dl>
            )
            : <p className="track-hint">{SELECT_POINT}</p>}
          {mode === 'recorded' && shownPoints.length > 0 && (
            <p className="track-playback-step">{selected ? `Selected position ${shownPoints.findIndex(point => point.id === selected.id) + 1} of ${shownPoints.length}` : `${shownPoints.length} available recorded positions`}</p>
          )}
          <details className="track-list">
            <summary>All positions</summary>
            <ol aria-label="Track points">
              {shownPoints.map(point => (
                <li key={point.id}>
                  <button type="button" aria-pressed={selectedId === point.id} onClick={() => onSelectPoint(point.id)}>
                    {formatWhen(point.ingestedAt)} · {coordinate(point)} · {formatSpeed(point.speedOverGroundKnots) ?? NOT_REPORTED}
                    {point.source ? ` · ${mode === 'recorded' || history?.context === 'RECORDED' ? 'Recorded ' : ''}${point.source}` : ''}
                  </button>
                </li>
              ))}
            </ol>
          </details>
        </>
      )}
      {history && history.context === 'RECORDED' && recordedCaption && (
        <p className="track-recorded-caption">{recordedCaption}</p>
      )}
      <details className="track-provenance">
        <summary>Track source details</summary>
        <dl>
          {history?.context && <ReadingText label="Context" value={history.context} />}
          {history?.timestampSemantics && <ReadingText label="Timestamp semantics" value={history.timestampSemantics} />}
          {history?.status && <ReadingText label="Availability" value={history.status} />}
          {history?.feedStatus && <ReadingText label="Feed status" value={history.feedStatus} />}
          {history?.captureId && <ReadingText label="Capture" value={history.captureId} />}
          {sourceShipmentId && <ReadingText label="Authorized source shipment" value={sourceShipmentId} />}
          {mappingVersion && <ReadingText label="Mapping version" value={mappingVersion} />}
          {history?.mmsi && <ReadingText label="MMSI" value={history.mmsi} />}
          {history?.fallbackUsed != null && <ReadingText label="Last-known fallback used" value={history.fallbackUsed ? 'Yes' : 'No'} />}
          {history?.lastKnownPosition && <ReadingText label="Last-known receipt time" value={history.lastKnownPosition.ingestedAt} />}
          {scenarioReferenceCutoff && <ReadingText label="Scenario reference cutoff" value={scenarioReferenceCutoff} />}
          {history?.cutoff && <ReadingText label="Recorded cutoff" value={history.cutoff} />}
        </dl>
      </details>
    </section>
  );
}

function trackContextLabel(history: VesselTrack, mode: TrackDisplayMode) {
  if (history.context === 'RECORDED') return 'Recorded AIS · receipt-time observations';
  if (history.context === 'SEEDED') return 'Seeded rehearsal data · not an authentic AIS capture';
  if (history.context === 'MIXED') return 'Mixed observation sources · inspect source details';
  if (history.context === 'LIVE_POSITION') return 'AIS observations near the live position window';
  if (history.context === 'UNAVAILABLE' || history.status === 'UNAVAILABLE') return 'Track unavailable';
  return mode === 'recorded' ? 'Recorded cutoff requested' : mode === 'scenario' ? 'Scenario positions' : null;
}

function toUtcInput(value: string | null) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? '' : date.toISOString().slice(0, 19);
}

function fromUtcInput(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)) return null;
  const withSeconds = value.length === 16 ? `${value}:00` : value;
  const date = new Date(`${withSeconds}Z`);
  return Number.isNaN(date.valueOf()) ? null : date.toISOString();
}

function coordinate(point: Pick<TrackPoint, 'latitude' | 'longitude'>) {
  return `${point.latitude.toFixed(4)}, ${point.longitude.toFixed(4)}`;
}

function Reading({ label, value }: { label: string; value: string | null }) {
  return <div><dt>{label}</dt><dd className={value === null ? 'is-missing' : undefined}>{value ?? NOT_REPORTED}</dd></div>;
}

function ReadingText({ label, value }: { label: string; value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>;
}
