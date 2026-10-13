import { afterEach, describe, expect, it, vi } from 'vitest';
import { archiveShipment, getShipment, linkShipmentImporter, listShipments, searchImporterOrganisations } from './api';
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
    });

    expect(fetch).toHaveBeenCalledWith('/api/shipments?reference=HBL+123&customer=A%26B+Logistics&arrivalDate=2026-10-12', expect.anything());
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
