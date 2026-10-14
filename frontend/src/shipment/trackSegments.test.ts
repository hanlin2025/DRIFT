import { describe, expect, it } from 'vitest';
import type { TrackPoint } from './api';
import { formatSpeed, hasEnoughHistory, isSlowSegment, slowdownCount, trackPoints, trackSegments } from './trackSegments';

function point(speed: number | null, longitude = 103.8): TrackPoint {
  return {
    latitude: 1.2,
    longitude,
    speedOverGroundKnots: speed,
    courseOverGroundDegrees: 90,
    trueHeadingDegrees: 91,
    ingestedAt: '2026-10-13T07:00:00Z',
  };
}

describe('track history helpers', () => {
  it('needs at least two points to draw a track', () => {
    expect(hasEnoughHistory([])).toBe(false);
    expect(hasEnoughHistory([point(5)])).toBe(false);
    expect(hasEnoughHistory([point(5), point(6)])).toBe(true);
  });

  it('marks a segment slow only when its reported speeds are below the threshold', () => {
    expect(isSlowSegment(point(2.9), point(0.4), 3)).toBe(true);
    expect(isSlowSegment(point(2.9), point(3), 3)).toBe(false);
    expect(isSlowSegment(point(12), point(1), 3)).toBe(false);
    expect(isSlowSegment(point(null), point(1), 3)).toBe(true);
    expect(isSlowSegment(point(null), point(null), 3)).toBe(false);
  });

  it('builds one line segment per consecutive pair in time order', () => {
    const points = [point(10, 103.1), point(2, 103.2), point(1, 103.3), point(9, 103.4)];

    const segments = trackSegments(points, 3);

    expect(segments.features.map(feature => feature.properties.slow)).toEqual([false, true, false]);
    expect(segments.features[1].geometry.coordinates).toEqual([[103.2, 1.2], [103.3, 1.2]]);
    expect(slowdownCount(points, 3)).toBe(1);
    expect(trackPoints(points, 3).features.map(feature => feature.properties.ingestedAt)).toEqual(points.map(point => point.ingestedAt));
  });

  it('rounds speed to one decimal for the point readout', () => {
    expect(formatSpeed(2.4)).toBe('2.4 kn');
    expect(formatSpeed(2.456)).toBe('2.5 kn');
    expect(formatSpeed(12)).toBe('12.0 kn');
    expect(formatSpeed(null)).toBeNull();
  });
});
