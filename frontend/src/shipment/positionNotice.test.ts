import { describe, expect, it } from 'vitest';
import type { ShipmentTracking } from './api';
import { MISSING_NOTICE, STALE_AFTER_MS, STALE_NOTICE, trackingNotice } from './positionNotice';

const now = Date.parse('2026-10-07T12:00:00Z');

function tracking(ingestedAt: string | null): ShipmentTracking {
  return {
    motherVesselName: 'MV Pacific Horizon',
    motherVessel: ingestedAt === null ? null : {
      mmsi: '563001234',
      vesselName: 'PACIFIC HORIZON',
      latitude: 1.264,
      longitude: 103.82,
      speedOverGroundKnots: 12.4,
      courseOverGroundDegrees: 175,
      trueHeadingDegrees: 176,
      ingestedAt,
    },
    feederVesselName: 'MV Strait Runner',
    feederVessel: null,
  };
}

describe('map position notices', () => {
  it('says when a shipment has no retained position', () => {
    expect(trackingNotice(tracking(null), now)).toBe(MISSING_NOTICE);
  });

  it('stays quiet while tracking has not loaded', () => {
    expect(trackingNotice(null, now)).toBeNull();
  });

  it('keeps a position that is exactly six hours old', () => {
    const ingestedAt = new Date(now - STALE_AFTER_MS).toISOString();
    expect(trackingNotice(tracking(ingestedAt), now)).toBeNull();
  });

  it('warns when the newest fix is older than six hours', () => {
    const ingestedAt = new Date(now - STALE_AFTER_MS - 1).toISOString();
    expect(trackingNotice(tracking(ingestedAt), now)).toBe(STALE_NOTICE);
  });
});
