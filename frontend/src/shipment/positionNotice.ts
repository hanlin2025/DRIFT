import type { ShipmentTracking, VesselPosition } from './api';

export const STALE_AFTER_MS = 6 * 60 * 60 * 1000;
export const STALE_NOTICE = 'Last known position - data may be outdated';
export const MISSING_NOTICE = 'No live AIS position for this shipment.';

export function isStalePosition(ingestedAt: string, now = Date.now()) {
  const ingested = Date.parse(ingestedAt);
  return Number.isFinite(ingested) && now - ingested > STALE_AFTER_MS;
}

export function trackingNotice(tracking: ShipmentTracking | null | undefined, now = Date.now()) {
  if (!tracking) return null;
  const positions = [tracking.motherVessel, tracking.feederVessel].filter((position): position is VesselPosition => position !== null);
  if (positions.length === 0) return MISSING_NOTICE;
  if (positions.some(position => isStalePosition(position.ingestedAt, now))) return STALE_NOTICE;
  return null;
}
