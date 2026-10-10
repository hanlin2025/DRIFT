import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { getShipment, getShipmentTracking, listShipments, updateShipment, type Shipment, type ShipmentTracking } from './api';
import { ShipmentDetail, TRACKING_POLL_MS } from './ShipmentDetail';

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
    open(forwarder, '/freight-forwarder');
    const user = userEvent.setup();
    const link = await screen.findByRole('link', { name: 'HL-1001' });
    expect(link).toHaveAttribute('href', '/freight-forwarder/shipments/7');
    await user.click(link);
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
    await user.click(screen.getByRole('link', { name: 'Back' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
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

  it('shows a loading skeleton until the shipment arrives', async () => {
    let arrive: (found: Shipment) => void = () => {};
    vi.mocked(getShipment).mockReturnValue(new Promise(resolve => { arrive = resolve; }));
    open(importer, '/importer/shipments/7');
    const loading = (await screen.findByText('Loading shipment...')).closest('[role="status"]');
    expect(loading).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    arrive(shipment);
    expect(await screen.findByRole('article', { name: 'Shipment HL-1001' })).toBeInTheDocument();
    expect(screen.queryByText('Loading shipment...')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit shipment' })).toBeInTheDocument();
  });

  it('replaces the loading skeleton with the error when the shipment is forbidden', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('You do not have access to this shipment', 403));
    open(importer, '/importer/shipments/8');
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have access to this shipment');
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
    expect(screen.queryByText('Loading shipment...')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('reports a missing shipment without a retry', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(importer, '/importer/shipments/99');
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment not found or has been removed.');
    expect(screen.getByRole('heading', { name: 'Shipment not found' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('shows the not found page for an invalid shipment id', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(importer, '/importer/shipments/not-a-number');
    expect(await screen.findByRole('heading', { name: 'Shipment not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Back/ })).toHaveAttribute('href', '/importer');
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

  it('refreshes the vessel position without leaving the detail page', async () => {
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracking)
      .mockResolvedValueOnce({
        ...tracking,
        motherVessel: { ...tracking.motherVessel!, latitude: 2.5 },
      });
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('PACIFIC HORIZON · 2.5, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/shipments/7');
    expect(getShipment).toHaveBeenCalledTimes(1);
    expect(getShipmentTracking).toHaveBeenCalledTimes(2);
  });

  it('polls tracking while the detail page stays open', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(getShipmentTracking).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
    expect(getShipmentTracking).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });

  it('keeps the newer position when an older tracking response arrives later', async () => {
    let resolveInitial: (value: ShipmentTracking) => void = () => {};
    const initial = new Promise<ShipmentTracking>(resolve => { resolveInitial = resolve; });
    let resolveRefresh: (value: ShipmentTracking) => void = () => {};
    const refreshed = new Promise<ShipmentTracking>(resolve => { resolveRefresh = resolve; });
    vi.mocked(getShipmentTracking).mockReturnValueOnce(initial).mockReturnValueOnce(refreshed);
    open(forwarder, '/freight-forwarder/shipments/7');
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Refresh' }));
    await act(async () => {
      resolveRefresh({ ...tracking, motherVessel: { ...tracking.motherVessel!, latitude: 2.5 } });
    });
    expect(await screen.findByText('PACIFIC HORIZON · 2.5, 103.82 · 12.4 kn')).toBeInTheDocument();
    await act(async () => { resolveInitial(tracking); });
    expect(screen.getByText('PACIFIC HORIZON · 2.5, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.queryByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).not.toBeInTheDocument();
  });

  it('does not apply a refresh from the previous shipment', async () => {
    let resolvePrevious: (value: ShipmentTracking) => void = () => {};
    const previous = new Promise<ShipmentTracking>(resolve => { resolvePrevious = resolve; });
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracking)
      .mockReturnValueOnce(previous)
      .mockResolvedValueOnce({ ...tracking, motherVessel: { ...tracking.motherVessel!, latitude: 9 } });
    const view = render(
      <MemoryRouter>
        <ShipmentDetail token="forwarder-token" shipmentId="7" basePath="/freight-forwarder" onSessionEnded={() => {}} />
      </MemoryRouter>,
    );
    expect(await screen.findByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    view.rerender(
      <MemoryRouter>
        <ShipmentDetail token="forwarder-token" shipmentId="8" basePath="/freight-forwarder" onSessionEnded={() => {}} />
      </MemoryRouter>,
    );
    expect(await screen.findByText('PACIFIC HORIZON · 9, 103.82 · 12.4 kn')).toBeInTheDocument();
    await act(async () => {
      resolvePrevious({ ...tracking, motherVessel: { ...tracking.motherVessel!, latitude: 3 } });
    });
    expect(screen.getByText('PACIFIC HORIZON · 9, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.queryByText('PACIFIC HORIZON · 3, 103.82 · 12.4 kn')).not.toBeInTheDocument();
  });

  it('shows no live position when tracking has loaded without a fix', async () => {
    vi.mocked(getShipmentTracking).mockResolvedValue({
      motherVesselName: shipment.motherVessel,
      motherVessel: null,
      feederVesselName: shipment.feederVessel,
      feederVessel: null,
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getAllByText('No live AIS position')).toHaveLength(2);
  });

  it('lets an importer edit all shipment fields and sends the hidden version token', async () => {
    const updated = { ...shipment, shipmentReference: 'HL-UPDATED', origin: 'Busan, KR', version: 4 };
    vi.mocked(updateShipment).mockResolvedValue(updated);
    open(importer, '/importer/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('HL-1001');
    expect(screen.getByLabelText('Origin')).toHaveValue('Singapore');
    expect(screen.queryByLabelText(/version/i)).not.toBeInTheDocument();

    vi.mocked(getShipment).mockResolvedValue(updated);

    await user.clear(screen.getByLabelText('Shipment reference'));
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-UPDATED');
    await user.clear(screen.getByLabelText('Origin'));
    await user.type(screen.getByLabelText('Origin'), 'Busan, KR');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    expect(await screen.findByRole('heading', { name: 'HL-UPDATED' })).toBeInTheDocument();
    expect(updateShipment).toHaveBeenCalledWith('importer-token', 7, expect.objectContaining({
      shipmentReference: 'HL-UPDATED', origin: 'Busan, KR', version: 3,
    }));
  });

  it('preserves seconds when saving an unchanged itinerary', async () => {
    const precise = {
      ...shipment,
      plannedMotherArrivalAt: '2026-10-02T00:00:30Z',
      plannedFeederDepartureAt: '2026-10-03T10:00:45Z',
      version: 4,
    };
    vi.mocked(getShipment).mockResolvedValue(precise);
    vi.mocked(updateShipment).mockResolvedValue(precise);
    open(importer, '/importer/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    expect(screen.getByLabelText('Planned mother-vessel arrival')).toHaveAttribute('step', '1');
    expect(screen.getByLabelText('Planned feeder-vessel departure')).toHaveAttribute('step', '1');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    const request = vi.mocked(updateShipment).mock.calls[0][2];
    expect(Date.parse(request.plannedMotherArrivalAt)).toBe(Date.parse(precise.plannedMotherArrivalAt));
    expect(Date.parse(request.plannedFeederDepartureAt)).toBe(Date.parse(precise.plannedFeederDepartureAt));
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

  it('returns to the existing unavailable detail state when an edit is no longer visible', async () => {
    vi.mocked(getShipment)
      .mockResolvedValueOnce(shipment)
      .mockRejectedValueOnce(new ApiError('Shipment not found', 404));
    vi.mocked(updateShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment not found or has been removed.');
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
