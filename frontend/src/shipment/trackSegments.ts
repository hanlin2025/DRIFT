import type { TrackPoint, TrackWindow } from './api';

export const TRACK_WINDOWS: readonly TrackWindow[] = [6, 24, 72];
export const DEFAULT_TRACK_WINDOW: TrackWindow = 24;

export const MIN_TRACK_POINTS = 2;
export const INSUFFICIENT_HISTORY = 'Insufficient history to draw a track for this window.';
export const NOT_REPORTED = 'Not reported';

type Position = [number, number];

export type TrackSegmentFeature = {
  type: 'Feature';
  properties: { slow: boolean };
  geometry: { type: 'LineString'; coordinates: Position[] };
};

export type TrackPointFeature = {
  type: 'Feature';
  properties: { ingestedAt: string; slow: boolean };
  geometry: { type: 'Point'; coordinates: Position };
};

export function hasEnoughHistory(points: readonly TrackPoint[]) {
  return points.length >= MIN_TRACK_POINTS;
}

export function isSlow(speed: number | null, threshold: number) {
  return speed != null && speed < threshold;
}

/**
 * A segment is a slowdown when every speed reported at its two ends is below the threshold.
 * A segment with no reported speed at either end is not marked.
 */
export function isSlowSegment(start: TrackPoint, end: TrackPoint, threshold: number) {
  const reported = [start.speedOverGroundKnots, end.speedOverGroundKnots].filter((speed): speed is number => speed != null);
  return reported.length > 0 && reported.every(speed => speed < threshold);
}

export function trackSegments(points: readonly TrackPoint[], threshold: number) {
  const features: TrackSegmentFeature[] = [];
  for (let index = 1; index < points.length; index += 1) {
    const start = points[index - 1];
    const end = points[index];
    features.push({
      type: 'Feature',
      properties: { slow: isSlowSegment(start, end, threshold) },
      geometry: { type: 'LineString', coordinates: [lngLat(start), lngLat(end)] },
    });
  }
  return { type: 'FeatureCollection' as const, features };
}

export function trackPoints(points: readonly TrackPoint[], threshold: number) {
  const features: TrackPointFeature[] = points.map(point => ({
    type: 'Feature',
    properties: { ingestedAt: point.ingestedAt, slow: isSlow(point.speedOverGroundKnots, threshold) },
    geometry: { type: 'Point', coordinates: lngLat(point) },
  }));
  return { type: 'FeatureCollection' as const, features };
}

export function slowdownCount(points: readonly TrackPoint[], threshold: number) {
  let count = 0;
  for (let index = 1; index < points.length; index += 1) {
    if (isSlowSegment(points[index - 1], points[index], threshold)) count += 1;
  }
  return count;
}

export function formatSpeed(knots: number | null) {
  return knots == null ? NOT_REPORTED : `${knots} kn`;
}

export function formatHeading(degrees: number | null) {
  if (degrees == null) return NOT_REPORTED;
  const compass = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'][Math.round((((degrees % 360) + 360) % 360) / 45) % 8];
  return `${degrees}° ${compass}`;
}

function lngLat(point: TrackPoint): Position {
  return [point.longitude, point.latitude];
}
