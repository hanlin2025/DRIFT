import portCatalog from '../../../backend/src/main/resources/ports/ports.json';

export type LngLat = [number, number];

const PORTS: Record<string, LngLat> = Object.fromEntries(
  Object.entries(portCatalog).map(([name, coordinates]) => {
    if (coordinates.length !== 2) {
      throw new Error(`Port catalog entry ${name} is not a longitude and latitude`);
    }
    return [name, [coordinates[0], coordinates[1]] as LngLat];
  }),
);

export function locate(place: string): LngLat | null {
  const key = place.trim().toLowerCase().replace(/^port of\s+/, '').split(',')[0]?.trim() ?? '';
  if (!key) return null;
  return PORTS[key] ?? null;
}

function toRad(degrees: number) {
  return degrees * Math.PI / 180;
}

function toDeg(radians: number) {
  return radians * 180 / Math.PI;
}

export function along(start: LngLat, end: LngLat, t: number): LngLat {
  const φ1 = toRad(start[1]);
  const λ1 = toRad(start[0]);
  const φ2 = toRad(end[1]);
  const λ2 = toRad(end[0]);
  const d = 2 * Math.asin(Math.sqrt(Math.sin((φ2 - φ1) / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin((λ2 - λ1) / 2) ** 2));
  if (d < 1e-8) return start;
  const a = Math.sin((1 - t) * d) / Math.sin(d);
  const b = Math.sin(t * d) / Math.sin(d);
  const x = a * Math.cos(φ1) * Math.cos(λ1) + b * Math.cos(φ2) * Math.cos(λ2);
  const y = a * Math.cos(φ1) * Math.sin(λ1) + b * Math.cos(φ2) * Math.sin(λ2);
  const z = a * Math.sin(φ1) + b * Math.sin(φ2);
  return [toDeg(Math.atan2(y, x)), toDeg(Math.atan2(z, Math.hypot(x, y)))];
}

export function arc(start: LngLat, end: LngLat, steps = 48): LngLat[] {
  return Array.from({ length: steps + 1 }, (_, index) => along(start, end, index / steps));
}
