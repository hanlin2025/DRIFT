import { useEffect, useState } from 'react';
import type { VesselPosition } from './api';
import { isStalePosition } from './positionNotice';
import { formatWhen } from './ShipmentForm';

export const AGE_TICK_MS = 30_000;
export const NO_POSITION = 'No position received yet';
export const STALE_DETAIL = 'Older than 6 hours. The vessel may have moved.';
export const NOT_REPORTED = 'Not reported';
const MINUTE_MS = 60_000;
const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];

export function VesselPanel({ name, position }: { name: string | null; position: VesselPosition | null }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), AGE_TICK_MS);
    return () => window.clearInterval(timer);
  }, [position]);

  const vessel = position?.vesselName?.trim() || name?.trim() || 'Not recorded';
  const stale = position !== null && isStalePosition(position.ingestedAt, now);

  return (
    <aside className="vessel-panel" aria-label="Mother vessel AIS">
      <p className="eyebrow">MOTHER VESSEL</p>
      <strong>{vessel}</strong>
      {position === null
        ? <p className="vessel-empty">{NO_POSITION}</p>
        : <>
            {position.mmsi && <p className="vessel-mmsi">MMSI {position.mmsi}</p>}
            {stale && <p className="vessel-stale"><span className="vessel-stale-badge">Stale</span> {STALE_DETAIL}</p>}
            <dl>
              <div><dt>Position</dt><dd>{formatCoordinates(position.latitude, position.longitude)}</dd></div>
              <Reading label="Speed" value={position.speedOverGroundKnots == null ? null : `${position.speedOverGroundKnots} kn`} />
              <Reading label="Course" value={formatBearing(position.courseOverGroundDegrees)} />
              <Reading label="Heading" value={formatBearing(position.trueHeadingDegrees)} />
              <div>
                <dt>Last report</dt>
                <dd><time dateTime={position.ingestedAt} title={formatWhen(position.ingestedAt)}>Updated {formatAge(position.ingestedAt, now)}</time></dd>
              </div>
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
