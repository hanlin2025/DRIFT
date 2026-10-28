import { afterEach, describe, expect, it, vi } from 'vitest';
import { archiveShipment, getShipment, getShipmentTracking, getVesselTrack, linkShipmentImporter, listShipments, restoreShipment, searchImporterOrganisations } from './api';
import { ApiError } from '../signup/api';

const shipment = {
  id: 9,
  shipmentReference: 'HL-1001',
  origin: 'Singapore',
  destination: 'Jakarta',
  transshipmentPort: 'Tanjung Pelepas',
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
  version: 0,
  connectionWindow: null,
  motherVesselPosition: null,
  feederVesselPosition: null,
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('shipment importer API', () => {
  it('reads a shipment list that has no importer field', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([shipment])));
    const listed = await listShipments('token');
    expect(listed).toHaveLength(1);
    expect(listed[0].importer).toBeNull();
    expect(listed[0].motherVesselImo).toBeNull();
    expect(listed[0].motherVesselNameWarning).toBeNull();
    expect(listed[0].arrivalComparison).toBeNull();
    expect(listed[0].updatedAt).toBeNull();
    expect(listed[0].updatedByName).toBeNull();
    expect(fetch).toHaveBeenCalledWith('/api/shipments', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer token' }),
    }));
  });

  it('sends only supported nonblank shipment filters', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([shipment])));

    await listShipments('token', {
      reference: 'HBL-2026',
      vessel: '',
      customer: 'STRAITS',
      arrivalDate: '',
    status: '',
      sort: '',
      atRisk: '',
    });

    expect(fetch).toHaveBeenCalledWith('/api/shipments?reference=HBL-2026&customer=STRAITS', expect.anything());
  });

  it('omits blank filters and encodes filter text with URLSearchParams', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([shipment])));

    await listShipments('token', {
      reference: 'HBL 123',
      vessel: '  ',
      customer: 'A&B Logistics',
      arrivalDate: '2026-10-12',
    status: '',
      sort: '',
      atRisk: '',
    });

    expect(fetch).toHaveBeenCalledWith('/api/shipments?reference=HBL+123&customer=A%26B+Logistics&arrivalDate=2026-10-12', expect.anything());
  });

  it('sends an explicit shipment status and never adds one when absent', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([shipment])));

    await listShipments('token', {
      reference: '', vessel: '', customer: '', arrivalDate: '', status: 'ARCHIVED', sort: '', atRisk: '',
    });

    expect(fetch).toHaveBeenCalledWith('/api/shipments?status=ARCHIVED', expect.anything());
  });

  it('reads who last updated a shipment on the list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([{
      ...shipment,
      updatedAt: '2026-10-18T02:15:00Z',
      updatedByName: 'Ada Admin',
    }])));
    const listed = await listShipments('token');
    expect(listed[0].updatedAt).toBe('2026-10-18T02:15:00Z');
    expect(listed[0].updatedByName).toBe('Ada Admin');
  });

  it('rejects a list shipment whose last update is not text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([{ ...shipment, updatedAt: 1, updatedByName: 'Ada Admin' }])));
    await expect(listShipments('token')).rejects.toEqual(expect.objectContaining({ status: 502 }));
  });

  it('sends the risk sort and the at-risk filter, keeping an invalid at-risk value for the API to reject', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => json([shipment])));

    await listShipments('token', {
      reference: '', vessel: '', customer: 'STRAITS', arrivalDate: '', status: '', sort: 'risk', atRisk: 'true',
    });
    expect(fetch).toHaveBeenLastCalledWith('/api/shipments?customer=STRAITS&sort=risk&atRisk=true', expect.anything());

    await listShipments('token', {
      reference: '', vessel: '', customer: '', arrivalDate: '', status: '', sort: 'risk', atRisk: '',
    });
    expect(fetch).toHaveBeenLastCalledWith('/api/shipments?sort=risk', expect.anything());

    await listShipments('token', {
      reference: '', vessel: '', customer: '', arrivalDate: '', status: '', sort: '', atRisk: 'false',
    });
    expect(fetch).toHaveBeenLastCalledWith('/api/shipments', expect.anything());

    await listShipments('token', {
      reference: '', vessel: '', customer: '', arrivalDate: '', status: '', sort: '', atRisk: 'maybe',
    });
    expect(fetch).toHaveBeenLastCalledWith('/api/shipments?atRisk=maybe', expect.anything());
  });

  it('reads the importer on a shipment detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      importer: { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' },
    })));
    const detail = await getShipment('token', '9');
    expect(detail.importer).toEqual({ id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' });
  });

  it('rejects a shipment whose importer payload is not an organisation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, importer: { id: '2' } })));
    await expect(getShipment('token', '9')).rejects.toEqual(expect.objectContaining({ status: 502 }));
  });

  it('searches importer organisations with the documented query', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      items: [{ id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' }],
      page: 0,
      size: 20,
      total: 1,
    })));
    const page = await searchImporterOrganisations('token', 'Straits');
    expect(page.items).toEqual([{ id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' }]);
    expect(fetch).toHaveBeenCalledWith('/api/organisations?type=importer&name=Straits&page=0&size=20', expect.anything());
  });

  it('sends a null importer id when unlinking', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, importer: null })));
    const linked = await linkShipmentImporter('token', 9, null);
    expect(linked.importer).toBeNull();
    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect(init).toEqual(expect.objectContaining({ method: 'PATCH', body: '{"importerCompanyId":null}' }));
    expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/api/shipments/9/link-importer');
  });

  it('returns the API message when the organisation cannot be linked', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'Invalid or inactive importer organisation selected.' }, 400)));
    const error = await linkShipmentImporter('token', 9, 2).catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ message: 'Invalid or inactive importer organisation selected.', status: 400 });
  });

  it('reads the owning company id rather than the linked importer id', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      companyId: 1,
      importer: { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' },
    })));
    const detail = await getShipment('token', '9');
    expect(detail.companyId).toBe(1);
    expect(detail.importer?.id).toBe(2);
  });

  it.each(['ACTIVE', 'ARCHIVED'] as const)('keeps a %s shipment status', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, status })));
    const detail = await getShipment('token', '9');
    expect(detail.status).toBe(status);
  });

  it.each([undefined, null, '', 'REMOVED'])('clears a missing or unknown status %s', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, status })));
    const detail = await getShipment('token', '9');
    expect(detail.status).toBeNull();
  });

  it.each([undefined, null, 0, -1, 1.5, '1'])('hides an invalid company id %s by clearing it', async companyId => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, companyId })));
    const detail = await getShipment('token', '9');
    expect(detail.companyId).toBeNull();
  });
});

describe('shipment tracking', () => {
  it('reads an explicit null warning as a clear and rejects a non-text warning', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      motherVesselName: 'MV Pacific Horizon',
      motherVessel: null,
      motherVesselNameWarning: null,
      feederVesselName: 'MV Strait Runner',
      feederVessel: {
        mmsi: '565000222',
        vesselName: 'STRAIT RUNNER',
        latitude: 1.25,
        longitude: 103.7,
        speedOverGroundKnots: 8.1,
        courseOverGroundDegrees: 10,
        trueHeadingDegrees: 11,
        ingestedAt: '2026-10-05T03:00:00Z',
      },
      feederVesselNameWarning: 'The AIS vessel name does not match the stored feeder vessel.',
    })));
    await expect(getShipmentTracking('token', '9')).resolves.toMatchObject({
      motherVesselNameWarning: null,
      feederVesselNameWarning: 'The AIS vessel name does not match the stored feeder vessel.',
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      motherVesselName: 'MV Pacific Horizon',
      motherVessel: null,
      motherVesselNameWarning: 1,
      feederVesselName: null,
      feederVessel: null,
      feederVesselNameWarning: null,
    })));
    await expect(getShipmentTracking('token', '9')).rejects.toMatchObject({ status: 502 });
  });
});

describe('archiveShipment', () => {
  it('patches the shipment status with the bearer token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ message: 'Shipment removed successfully' }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(archiveShipment('token-1', 7)).resolves.toBe('Shipment removed successfully');
    expect(fetchMock).toHaveBeenCalledWith('/api/shipments/7/status', expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ status: 'ARCHIVED' }),
      cache: 'no-store',
      headers: expect.objectContaining({
        Authorization: 'Bearer token-1',
        'Content-Type': 'application/json',
      }),
    }));
  });

  it('treats any non-blank message on a successful response as success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'Archived and retained.' })));
    await expect(archiveShipment('token-1', 12)).resolves.toBe('Archived and retained.');
  });

  it('rejects a successful response that does not include a message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status: 'ARCHIVED' })));
    await expect(archiveShipment('token-1', 7)).rejects.toMatchObject({ status: 502 });
  });

  it('does not treat a non-blank message on an error response as success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'Shipment removed successfully' }, 403)));
    await expect(archiveShipment('token-1', 7)).rejects.toMatchObject({
      status: 403,
      message: 'Shipment removed successfully',
    });
  });
});

describe('restoreShipment', () => {
  it('patches the shipment back to active with the bearer token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ message: 'Shipment restored successfully' }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(restoreShipment('token-1', 7)).resolves.toBe('Shipment restored successfully');
    expect(fetchMock).toHaveBeenCalledWith('/api/shipments/7/status', expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ status: 'ACTIVE' }),
      cache: 'no-store',
      headers: expect.objectContaining({
        Authorization: 'Bearer token-1',
        'Content-Type': 'application/json',
      }),
    }));
  });

  it('rejects a successful response that does not include a message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status: 'ACTIVE' })));
    await expect(restoreShipment('token-1', 7)).rejects.toMatchObject({ status: 502 });
  });

  it.each([
    [403, 'You do not have permission to restore this shipment.'],
    [404, 'Shipment not found'],
    [409, 'This shipment is already active.'],
  ] as const)('returns the server message for status %s', async (status, message) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message }, status)));
    await expect(restoreShipment('token-1', 7)).rejects.toMatchObject({ status, message });
  });
});

describe('arrival comparison parsing', () => {
  const limitation = 'Approximate arrival — based on straight-line distance and current speed.';

  function available(classification: 'AHEAD_OF_PLAN' | 'ON_SCHEDULE' | 'BEHIND_PLAN', difference: string, possiblyOutdated = false) {
    return {
      estimatedArrivalAt: '2026-10-02T01:30:00Z',
      limitation,
      speedOverGroundKnots: 12.375,
      classification,
      difference,
      differenceSeconds: classification === 'AHEAD_OF_PLAN' ? -5400 : 5400,
      differenceNanos: classification === 'AHEAD_OF_PLAN' ? -1 : 0,
      lastReceivedAt: '2026-10-01T18:00:00.123456Z',
      possiblyOutdated,
      unavailableReason: null,
    };
  }

  function unavailable(code: string, message: string) {
    return {
      estimatedArrivalAt: null,
      limitation: null,
      speedOverGroundKnots: null,
      classification: null,
      difference: null,
      differenceSeconds: null,
      differenceNanos: null,
      lastReceivedAt: null,
      possiblyOutdated: null,
      unavailableReason: { code, message },
    };
  }

  it('reads a remaining window and a missed connection, and rejects a contradictory one', async () => {
    const available = {
      status: 'AVAILABLE',
      duration: '4 hours',
      totalSeconds: 14400,
      missedBy: null,
      missedBySeconds: null,
    };
    const missed = {
      status: 'MISSED',
      duration: null,
      totalSeconds: null,
      missedBy: '2 hours',
      missedBySeconds: 7200,
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, remainingConnectionWindow: available })));
    await expect(getShipment('token', '9')).resolves.toMatchObject({ remainingConnectionWindow: available });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([{ ...shipment, remainingConnectionWindow: missed }])));
    await expect(listShipments('token')).resolves.toMatchObject([{ remainingConnectionWindow: missed }]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      remainingConnectionWindow: { status: 'AVAILABLE', duration: '4 hours', totalSeconds: 14400, missedBy: '1 hour', missedBySeconds: 3600 },
    })));
    await expect(getShipment('token', '9')).rejects.toMatchObject({ status: 502 });
  });

  it('reads a detail response that omits the comparison', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(shipment)));
    const detail = await getShipment('token', '9');
    expect(detail.arrivalComparison).toBeNull();
    expect(detail.connectionWindow).toBeNull();
    expect(detail.remainingConnectionWindow).toBeNull();
  });

  it('reads an explicit null comparison on a list item', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([{ ...shipment, arrivalComparison: null }])));
    const listed = await listShipments('token');
    expect(listed[0].arrivalComparison).toBeNull();
  });

  it.each([
    ['AHEAD_OF_PLAN', 'Ahead of plan · estimated 1h 30m earlier than planned'],
    ['ON_SCHEDULE', 'On schedule · estimated 1h 30m later than planned'],
    ['BEHIND_PLAN', 'Behind plan · estimated 6h later than planned'],
  ] as const)('reads an available %s comparison', async (classification, difference) => {
    const comparison = available(classification, difference, classification === 'BEHIND_PLAN');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, arrivalComparison: comparison })));
    const detail = await getShipment('token', '9');
    expect(detail.arrivalComparison).toEqual(comparison);
  });

  it('reads an unavailable comparison with null arrival fields', async () => {
    const comparison = unavailable('NO_POSITION', 'No AIS position has been received for the mother vessel.');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, arrivalComparison: comparison })));
    const detail = await getShipment('token', '9');
    expect(detail.arrivalComparison).toEqual(comparison);
    expect(detail.arrivalComparison?.estimatedArrivalAt).toBeNull();
  });

  it.each([
    'soon',
    { estimatedArrivalAt: '2026-10-02T01:30:00Z' },
    { ...available('ON_SCHEDULE', 'On schedule · estimated 1h 30m later than planned'), unavailableReason: { code: 'NO_POSITION', message: 'No AIS position has been received for the mother vessel.' } },
    { ...unavailable('NO_POSITION', 'No AIS position has been received for the mother vessel.'), estimatedArrivalAt: '2026-10-02T01:30:00Z' },
    { ...available('ON_SCHEDULE', 'On schedule · estimated at the planned time'), classification: 'LATE' },
    { ...unavailable('NO_POSITION', 'No AIS position has been received for the mother vessel.'), possiblyOutdated: false },
  ])('rejects a malformed comparison instead of treating it as omitted %#', async comparison => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...shipment, arrivalComparison: comparison })));
    await expect(getShipment('token', '9')).rejects.toMatchObject({ status: 502 });
  });

  it('rejects a list when one comparison is malformed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([
      shipment,
      { ...shipment, id: 10, arrivalComparison: { estimatedArrivalAt: '2026-10-02T01:30:00Z' } },
    ])));
    await expect(listShipments('token')).rejects.toMatchObject({ status: 502 });
  });
});

describe('archiveShipment', () => {
  it.each([
    [400, 'Invalid Shipment ID.'],
    [403, 'You do not have permission to archive this shipment.'],
    [404, 'Shipment not found'],
    [409, 'Shipment is already removed or does not exist.'],
  ] as const)('returns the server message for status %s', async (status, message) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message }, status)));
    await expect(archiveShipment('token-1', 7)).rejects.toMatchObject({ status, message });
  });
});

const track = {
  vessel: 'FEEDER',
  vesselName: 'MV Strait Runner',
  mmsi: '533000111',
  windowHours: 6,
  from: '2026-10-13T06:00:00Z',
  to: '2026-10-13T12:00:00Z',
  slowdownThresholdKnots: 3,
  pointLimit: 500,
  truncated: false,
  points: [
    { id: 7001, latitude: 1.2, longitude: 103.8, speedOverGroundKnots: 2.4, courseOverGroundDegrees: 90, trueHeadingDegrees: 91, ingestedAt: '2026-10-13T07:00:00Z' },
    { id: 7002, latitude: 1.3, longitude: 103.9, speedOverGroundKnots: null, courseOverGroundDegrees: null, trueHeadingDegrees: null, ingestedAt: '2026-10-13T08:00:00Z' },
  ],
};

describe('vessel track history API', () => {
  it('requests the chosen vessel and window and keeps the points in order', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(track)));

    const found = await getVesselTrack('token', '7', 'FEEDER', 6);

    expect(fetch).toHaveBeenCalledWith('/api/shipments/7/track-history?vessel=FEEDER&hours=6', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer token' }),
    }));
    expect(found.points.map(point => point.ingestedAt)).toEqual(['2026-10-13T07:00:00Z', '2026-10-13T08:00:00Z']);
    expect(found.slowdownThresholdKnots).toBe(3);
  });

  it('accepts a vessel with no report', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...track, vessel: 'MOTHER', mmsi: null, points: [] })));

    const found = await getVesselTrack('token', '7', 'MOTHER', 24);

    expect(found.mmsi).toBeNull();
    expect(found.points).toEqual([]);
  });

  it('rejects a malformed track', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...track, points: [{ latitude: 'north' }] })));

    await expect(getVesselTrack('token', '7', 'FEEDER', 6)).rejects.toMatchObject({ status: 502 });
  });

  it('rejects a point without an integer observation id', async () => {
    const [first, second] = track.points;
    const { id: _id, ...withoutId } = first;
    for (const points of [[withoutId, second], [{ ...first, id: '7001' }, second], [{ ...first, id: 1.5 }, second]]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...track, points })));
      await expect(getVesselTrack('token', '7', 'FEEDER', 6)).rejects.toMatchObject({ status: 502 });
    }
  });

  it('keeps a stored AIS source and rejects one that is not AIS_STREAM or SEEDED', async () => {
    const position = {
      mmsi: '565000222',
      vesselName: 'STRAIT RUNNER',
      latitude: 1.25,
      longitude: 103.7,
      speedOverGroundKnots: 8.1,
      courseOverGroundDegrees: 10,
      trueHeadingDegrees: 11,
      ingestedAt: '2026-10-05T03:00:00Z',
      source: 'SEEDED',
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      motherVesselName: 'MV Pacific Horizon',
      motherVessel: { ...position, source: 'AIS_STREAM' },
      motherVesselNameWarning: null,
      feederVesselName: 'MV Strait Runner',
      feederVessel: position,
      feederVesselNameWarning: null,
    })));
    await expect(getShipmentTracking('token', '9')).resolves.toMatchObject({
      motherVessel: { source: 'AIS_STREAM' },
      feederVessel: { source: 'SEEDED' },
    });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      motherVesselPosition: { ...position, source: 'LIVE' },
    })));
    await expect(getShipment('token', '9')).rejects.toMatchObject({ status: 502 });
  });

  it('keeps an operational state and treats an omitted one as absent', async () => {
    const state = {
      label: 'At anchor',
      description: 'At anchor near Singapore',
      unreliable: false,
      ingestedAt: '2026-10-05T03:00:00Z',
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      motherOperationalState: state,
      feederOperationalState: { ...state, label: 'Unknown', description: 'Unknown', ingestedAt: null },
    })));
    const detail = await getShipment('token', '9');
    expect(detail.motherOperationalState).toEqual(state);
    expect(detail.feederOperationalState?.ingestedAt).toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(shipment)));
    const omitted = await getShipment('token', '9');
    expect(omitted.motherOperationalState).toBeNull();
    expect(omitted.feederOperationalState).toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      motherOperationalState: { ...state, description: 'Moored near Singapore' },
    })));
    await expect(getShipment('token', '9')).rejects.toMatchObject({ status: 502 });
  });

  it('keeps the AIS feed status and rejects a feed that is not Live, Delayed, or Offline', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      aisFeed: { status: 'OFFLINE', lastReceivedAt: '2026-10-05T03:00:00Z' },
    })));
    await expect(getShipment('token', '9')).resolves.toMatchObject({
      aisFeed: { status: 'OFFLINE', lastReceivedAt: '2026-10-05T03:00:00Z' },
    });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      aisFeed: { status: 'LIVE', lastReceivedAt: '2026-10-22T06:00:00Z' },
    })));
    await expect(getShipment('token', '9')).resolves.toMatchObject({
      aisFeed: { status: 'LIVE', lastReceivedAt: '2026-10-22T06:00:00Z' },
    });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(shipment)));
    const omitted = await getShipment('token', '9');
    expect(omitted.aisFeed).toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      aisFeed: { status: 'OFFLINE', lastReceivedAt: null },
    })));
    await expect(getShipment('token', '9')).resolves.toMatchObject({
      aisFeed: { status: 'OFFLINE', lastReceivedAt: null },
    });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      aisFeed: { status: 'LIVE', lastReceivedAt: null },
    })));
    await expect(getShipment('token', '9')).rejects.toMatchObject({ status: 502 });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...shipment,
      aisFeed: { status: 'STALE', lastReceivedAt: '2026-10-22T06:00:00Z' },
    })));
    await expect(getShipment('token', '9')).rejects.toMatchObject({ status: 502 });
  });

  it('passes the API message for an unsupported window', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      message: 'Shipment information is missing or invalid',
      errors: { hours: 'Window must be 6, 24 or 72 hours' },
    }, 400)));

    await expect(getVesselTrack('token', '7', 'MOTHER', 24)).rejects.toBeInstanceOf(ApiError);
  });
});
