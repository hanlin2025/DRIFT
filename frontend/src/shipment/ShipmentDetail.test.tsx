import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { getShipment, getShipmentTracking, listShipments, updateShipment, type Shipment, type ShipmentTracking } from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ createShipment: vi.fn(), updateShipment: vi.fn(), listShipments: vi.fn(), getShipment: vi.fn(), getShipmentTracking: vi.fn() }));

const importer: Session = {
  token: 'importer-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 8,
  fullName: 'Ivan Lim',
  email: 'ivan@example.com',
  role: 'IMPORTER',
  company: { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' },
};

const forwarder: Session = { ...importer, token: 'forwarder-token', id: 4, role: 'FREIGHT_FORWARDER' };

const shipment: Shipment = {
  id: 7,
  shipmentReference: 'HL-1001',
  origin: 'Singapore',
  destination: 'Jakarta',
  transshipmentPort: 'Singapore',
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
  version: 3,
  connectionWindow: { duration: '1 day 10 hours', totalSeconds: 122400 },
  motherVesselPosition: {
    mmsi: '563001234',
    vesselName: 'PACIFIC HORIZON',
    latitude: 1.264,
    longitude: 103.82,
    speedOverGroundKnots: 12.4,
    courseOverGroundDegrees: 175,
    trueHeadingDegrees: 176,
    ingestedAt: '2026-10-05T03:00:00Z',
  },
  feederVesselPosition: null,
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function open(account: Session, path: string) {
  writeSession(account);
  vi.mocked(loadSession).mockResolvedValue({ ...account, token: account.token });
  return render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter>);
}

const tracking: ShipmentTracking = {
  motherVesselName: shipment.motherVessel,
  motherVessel: shipment.motherVesselPosition,
  feederVesselName: shipment.feederVessel,
  feederVessel: shipment.feederVesselPosition,
};

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(listShipments).mockResolvedValue([shipment]);
  vi.mocked(getShipment).mockResolvedValue(shipment);
  vi.mocked(getShipmentTracking).mockResolvedValue(tracking);
});

describe('connection window on the shipment detail', () => {
  it('shows the planned window on the shipment detail', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/shipments/7');
    expect(getShipment).toHaveBeenCalledWith('forwarder-token', '7');
    expect(getShipmentTracking).toHaveBeenCalledWith('forwarder-token', '7');
    const record = screen.getByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getAllByText('Singapore')).toHaveLength(2);
    expect(within(record).getByText('Origin')).toBeInTheDocument();
    expect(within(record).getByText('Destination')).toBeInTheDocument();
    expect(within(record).getByText('Registered')).toBeInTheDocument();
    expect(within(record).getByText('Connection window')).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(within(record).getByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('No live AIS position')).toBeInTheDocument();
    expect(screen.getByRole('figure', { name: 'Planned route' })).toBeInTheDocument();
    expect(await screen.findByText(/MOTHER LIVE PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn · Last updated/)).toBeInTheDocument();
    expect(screen.queryByText(/FEEDER LIVE/)).not.toBeInTheDocument();
  });

  it('shows when the shipment has no planned connection window', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      connectionWindow: null,
      motherVesselPosition: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({
      motherVesselName: shipment.motherVessel,
      motherVessel: null,
      feederVesselName: shipment.feederVessel,
      feederVessel: null,
    });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('No planned connection window')).toBeInTheDocument();
    expect(within(record).getAllByText('No live AIS position')).toHaveLength(2);
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/importer');
  });

  it('shows a pending feeder vessel and departure when none is assigned', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      feederVessel: null,
      plannedFeederDepartureAt: null,
      connectionWindow: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({ ...tracking, feederVesselName: null, feederVessel: null });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    const pending = within(record).getAllByText('Pending assignment');
    expect(pending).toHaveLength(2);
    pending.forEach(field => expect(field).toHaveClass('is-missing'));
    expect(within(record).getByText('No planned connection window')).toBeInTheDocument();
    expect(within(record).getByText('No live AIS position')).toBeInTheDocument();
    expect(screen.getByText('FEEDER Pending assignment')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps the recorded position in the facts when tracking cannot be loaded', async () => {
    vi.mocked(getShipmentTracking).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.queryByText(/MOTHER LIVE/)).not.toBeInTheDocument();
    expect(screen.queryByText(/FEEDER LIVE/)).not.toBeInTheDocument();
  });

  it('reports a missing shipment without a retry', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(importer, '/importer/shipments/99');
    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment is not available.');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('retries a failed detail request', async () => {
    vi.mocked(getShipment)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce(shipment);
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT.');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('article', { name: 'Shipment HL-1001' })).toBeInTheDocument();
  });

  it('ends the session when the detail request is rejected', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });

  it("sends another role's shipment link to that role's shipment address", async () => {
    open(importer, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/importer/shipments/7');
    expect(getShipment).toHaveBeenCalledWith('importer-token', '7');
  });

  it('lets an importer edit all shipment fields and sends the hidden version token', async () => {
    const updated = { ...shipment, shipmentReference: 'HL-UPDATED', origin: 'Busan, KR', version: 4 };
    const refreshedTracking: ShipmentTracking = {
      motherVesselName: updated.motherVessel,
      motherVessel: { ...shipment.motherVesselPosition!, vesselName: 'NEW MOTHER POSITION', latitude: 1.31 },
      feederVesselName: updated.feederVessel,
      feederVessel: null,
    };
    vi.mocked(updateShipment).mockResolvedValue(updated);
    vi.mocked(getShipment).mockReset();
    vi.mocked(getShipment).mockResolvedValueOnce(shipment).mockResolvedValueOnce(updated);
    vi.mocked(getShipmentTracking).mockReset();
    vi.mocked(getShipmentTracking).mockResolvedValueOnce(tracking).mockResolvedValueOnce(refreshedTracking);
    open(importer, '/importer/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('HL-1001');
    expect(screen.getByLabelText('Origin')).toHaveValue('Singapore');
    expect(screen.queryByLabelText(/version/i)).not.toBeInTheDocument();

    await user.clear(screen.getByLabelText('Shipment reference'));
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-UPDATED');
    await user.clear(screen.getByLabelText('Origin'));
    await user.type(screen.getByLabelText('Origin'), 'Busan, KR');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    expect(await screen.findByRole('heading', { name: 'HL-UPDATED' })).toBeInTheDocument();
    expect(updateShipment).toHaveBeenCalledWith('importer-token', 7, expect.objectContaining({
      shipmentReference: 'HL-UPDATED', origin: 'Busan, KR', version: 3,
    }));
    expect(getShipmentTracking).toHaveBeenCalledTimes(2);
    expect(await screen.findByText(/NEW MOTHER POSITION/)).toBeInTheDocument();
  });

  it('shows duplicate-reference and stale-version conflicts differently', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('A shipment with this reference already exists for your company', 409));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A shipment with this reference already exists for your company');
    expect(screen.getByLabelText('Shipment reference')).toHaveAttribute('aria-invalid', 'true');

    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('This shipment was updated by another user. Refresh it and try again.', 409));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment was updated by someone else while you were editing it. Reload the latest details before trying again.');
    await user.click(screen.getByRole('button', { name: 'Reload latest details' }));
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
  });

  it('reloads and displays the server state after a stale update conflict', async () => {
    const refreshed = { ...shipment, shipmentReference: 'HL-SERVER-CURRENT', origin: 'Busan, KR', version: 4 };
    vi.mocked(getShipment).mockReset();
    vi.mocked(getShipment).mockResolvedValueOnce(shipment).mockResolvedValueOnce(refreshed);
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('This shipment was updated by another user. Refresh it and try again.', 409));
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    await user.clear(screen.getByLabelText('Shipment reference'));
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-STALE-LOCAL');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment was updated by someone else while you were editing it.');
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('HL-STALE-LOCAL');
    await user.click(screen.getByRole('button', { name: 'Reload latest details' }));

    expect(await screen.findByRole('heading', { name: 'HL-SERVER-CURRENT' })).toBeInTheDocument();
    expect(screen.queryByDisplayValue('HL-STALE-LOCAL')).not.toBeInTheDocument();
    expect(getShipment).toHaveBeenCalledTimes(2);
    expect(updateShipment).toHaveBeenCalledTimes(1);
  });

  it('keeps edit mode and unsaved values when the backend rejects the update', async () => {
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('Shipment information is missing or invalid', 400, {
      shipmentReference: 'Shipment reference is required',
    }));
    open(importer, '/importer/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    await user.clear(screen.getByLabelText('Shipment reference'));
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-UNSAVED');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment information is missing or invalid');
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('HL-UNSAVED');
    expect(screen.getByText('Shipment reference is required')).toBeInTheDocument();
  });

  it('returns to the existing unavailable detail state when an edit is no longer visible', async () => {
    vi.mocked(getShipment)
      .mockResolvedValueOnce(shipment)
      .mockRejectedValueOnce(new ApiError('Shipment not found', 404));
    vi.mocked(updateShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment is not available.');
  });
});

describe('shipment detail decoding', () => {
  async function decode(body: unknown) {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));
    const api = await vi.importActual<typeof import('./api')>('./api');
    return api.getShipment('token', '7').finally(() => vi.unstubAllGlobals());
  }

  it('accepts an explicitly unassigned feeder vessel', async () => {
    await expect(decode({ ...shipment, feederVessel: null, plannedFeederDepartureAt: null }))
      .resolves.toMatchObject({ feederVessel: null, plannedFeederDepartureAt: null });
  });

  it('rejects a response that omits the feeder vessel fields', async () => {
    const { feederVessel: _vessel, plannedFeederDepartureAt: _departure, ...omitted } = shipment;
    await expect(decode(omitted)).rejects.toMatchObject({ status: 502 });
  });
});
