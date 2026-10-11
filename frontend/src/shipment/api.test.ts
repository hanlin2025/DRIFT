import { afterEach, describe, expect, it, vi } from 'vitest';
import { getShipment, linkShipmentImporter, listShipments, searchImporterOrganisations } from './api';
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
});
