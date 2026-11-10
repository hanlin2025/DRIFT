import { useEffect, useState } from 'react';
import type { TrackPoint, TrackVessel, VesselPosition, VesselTrack } from './api';
import type { TrackDisplayMode, TrackPresentationOwnership, TrackReadState } from './trackPresentationContract';
import { isStalePosition } from './positionNotice';
import { formatWhen } from './ShipmentForm';

export const AGE_TICK_MS = 30_000;
export const NO_POSITION = 'No position received yet';
export const STALE_DETAIL = 'Older than 6 hours. The vessel may have moved.';
export const NOT_REPORTED = 'Not reported';
const MINUTE_MS = 60_000;
const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];

export type VesselPanelView = {
  role: TrackVessel;
  mode: TrackDisplayMode;
  ownershipMode?: TrackPresentationOwnership;
  history?: VesselTrack | null;
  selectedPointId?: number | null;
  referenceTime?: string | null;
  state?: TrackReadState;
  message?: string | null;
  identityName?: string | null;
};

type DisplayPosition = Pick<TrackPoint, 'latitude' | 'longitude' | 'speedOverGroundKnots' | 'courseOverGroundDegrees' | 'trueHeadingDegrees' | 'ingestedAt'> & {
  mmsi?: string | null;
  source?: string;
  lastKnown?: boolean;
};

export function VesselPanel({ name, position, view }: { name: string | null; position: VesselPosition | null; view?: VesselPanelView }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (view && (view.ownershipMode === 'controlled' || view.mode !== 'live')) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), AGE_TICK_MS);
    return () => window.clearInterval(timer);
  }, [position, view?.ownershipMode, view?.mode]);

  const history = view?.history ?? null;
  const selectedTrackPoint = view?.selectedPointId == null
    ? null
    : history?.points.find(point => point.id === view.selectedPointId) ?? null;
  const lastKnown = view?.mode === 'recorded' && history?.fallbackUsed ? history.lastKnownPosition ?? null : null;
  const point = view?.mode === 'recorded' ? selectedTrackPoint ?? lastKnown ?? history?.points.at(-1) ?? null : null;
  const displayed: DisplayPosition | null = point
    ? { ...point, mmsi: history?.mmsi, lastKnown: point === lastKnown }
    : position;
  const liveName = displayed && 'vesselName' in displayed && typeof displayed.vesselName === 'string' ? displayed.vesselName.trim() : null;
  const vessel = view?.identityName?.trim() || (view?.mode === 'recorded' ? history?.vesselName?.trim() : liveName) || name?.trim() || 'Not recorded';
  const displayTime = view?.referenceTime ? Date.parse(view.referenceTime) : now;
  const stale = view?.mode === 'recorded'
    ? history?.status === 'STALE'
    : displayed !== null && isStalePosition(displayed.ingestedAt,
      view?.referenceTime && Number.isFinite(displayTime) ? displayTime : now);
  const roleLabel = view?.role ?? 'MOTHER';
  const recorded = view?.mode === 'recorded' && history?.context === 'RECORDED';
  const seeded = history?.context === 'SEEDED' || ((view?.mode == null || view.mode === 'live') && displayed?.source === 'SEEDED');

  return (
    <aside className="vessel-panel" aria-label={`${roleLabel === 'MOTHER' ? 'Mother' : 'Feeder'} vessel AIS`}>
      <p className="eyebrow">{roleLabel} VESSEL</p>
      <strong>{vessel}</strong>
      {displayed === null
        ? <p className="vessel-empty">{view?.state === 'loading' ? 'Loading position…' : view?.message ?? (view?.mode === 'recorded' ? 'No recorded position is available.' : NO_POSITION)}</p>
        : <>
            {displayed.mmsi && <p className="vessel-mmsi">MMSI {displayed.mmsi}</p>}
            {recorded && <p className="vessel-recorded">Recorded AIS · receipt time. This is not the vessel’s current physical position.</p>}
            {seeded && <p className="vessel-seeded"><span className="vessel-seeded-badge">Seeded</span> Rehearsal data; not an authentic AIS capture.</p>}
            {view?.mode === 'scenario' && <p className="vessel-scenario">Scenario position · used for scenario display only.</p>}
            {displayed.lastKnown && <p className="vessel-stale">Last known position at the requested cutoff.</p>}
            {stale && <p className="vessel-stale"><span className="vessel-stale-badge">Stale</span> {view?.mode === 'recorded' ? 'This track is stale at its receipt-time cutoff.' : STALE_DETAIL}</p>}
            {view?.mode === 'recorded' && history?.feedStatus === 'OFFLINE' && <p className="vessel-status">The AIS feed is offline; this stored observation remains available.</p>}
            <dl>
              <div><dt>Position</dt><dd>{formatCoordinates(displayed.latitude, displayed.longitude)}</dd></div>
              <Reading label="Speed" value={displayed.speedOverGroundKnots == null ? null : `${displayed.speedOverGroundKnots} kn`} />
              <Reading label="Course" value={formatBearing(displayed.courseOverGroundDegrees)} />
              <Reading label="Heading" value={formatBearing(displayed.trueHeadingDegrees)} />
              {view?.mode === 'scenario'
                ? <div><dt>Simulation time</dt><dd>{view.referenceTime
                    ? <>At simulated cutoff <time dateTime={view.referenceTime}>{view.referenceTime}</time></>
                    : 'At simulated cutoff: Not recorded'}</dd></div>
                : <div>
                    <dt>{view?.mode === 'recorded' ? 'Receipt time' : 'Last report'}</dt>
                    <dd><time dateTime={displayed.ingestedAt} title={formatWhen(displayed.ingestedAt)}>{view?.mode === 'recorded' ? formatWhen(displayed.ingestedAt) : `Updated ${formatAge(displayed.ingestedAt, Number.isFinite(displayTime) ? displayTime : now)}`}</time></dd>
                  </div>}
            </dl>
          </>}
    </aside>
  );
}

function Reading({ label, value }: { label: string; value: string | null }) {
  return <div><dt>{label}</dt><dd className={value === null ? 'is-missing' : undefined}>{value ?? NOT_REPORTED}</dd></div>;
}

export function formatCoordinates(latitude: number, longitude: number) {
  const lat = `${Math.abs(latitude).toFixed(4)}° ${latitude < 0 ? 'S' : 'N'}`;
  const lon = `${Math.abs(longitude).toFixed(4)}° ${longitude < 0 ? 'W' : 'E'}`;
  return `${lat}, ${lon}`;
}

export function compassPoint(degrees: number) {
  const normalised = ((degrees % 360) + 360) % 360;
  return COMPASS[Math.round(normalised / 45) % COMPASS.length];
}

export function formatBearing(degrees: number | null) {
  if (degrees == null) return null;
  return `${degrees}° (${compassPoint(degrees)})`;
}

export function formatAge(ingestedAt: string, now = Date.now()) {
  const ingested = Date.parse(ingestedAt);
  if (!Number.isFinite(ingested)) return ingestedAt;
  const minutes = Math.max(0, Math.floor((now - ingested) / MINUTE_MS));
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h ago` : `${hours} h ${rest} min ago`;
  }
  return `${Math.floor(hours / 24)} d ago`;
}
