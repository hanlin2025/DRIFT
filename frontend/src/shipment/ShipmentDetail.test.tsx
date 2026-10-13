import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { archiveShipment, getShipment, getShipmentTracking, listShipments, updateShipment, type ArrivalClassification, type ArrivalComparison, type Shipment, type ShipmentTracking } from './api';
import { ShipmentDetail, TRACKING_POLL_MS } from './ShipmentDetail';
import { formatWhen } from './ShipmentForm';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ createShipment: vi.fn(), updateShipment: vi.fn(), listShipments: vi.fn(), getShipment: vi.fn(), getShipmentTracking: vi.fn(), archiveShipment: vi.fn() }));

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
const admin: Session = { ...forwarder, token: 'admin-token', id: 2, role: 'ADMIN' };
const manager: Session = { ...forwarder, token: 'manager-token', id: 3, role: 'LOGISTICS_MANAGER' };

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
  companyId: 2,
  connectionWindow: { duration: '1 day 10 hours', totalSeconds: 122400 },
  risk: { level: 'LOW', explanation: 'At least 24 hours to transfer cargo between vessels.' },
  status: null,
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
  arrivalComparison: null,
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function open(account: Session, path: string) {
  writeSession(account);
  vi.mocked(loadSession).mockResolvedValue({ ...account, token: account.token });
  return render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter>);
}

const MOTHER_WARNING = 'The AIS vessel name does not match the stored mother vessel.';
const FEEDER_WARNING = 'The AIS vessel name does not match the stored feeder vessel.';

function namedFix(vesselName: string, latitude: number) {
  return { ...shipment.motherVesselPosition!, vesselName, latitude };
}

function positionText(record: HTMLElement, text: string) {
  return within(record).getByText((_, node) => node?.tagName === 'DD' && node.textContent === text);
}

async function findPosition(record: HTMLElement, text: string) {
  return within(record).findByText((_, node) => node?.tagName === 'DD' && node.textContent === text);
}

function tracked(motherName: string, motherWarning: string | null, feederName: string, feederWarning: string | null, motherLatitude: number, feederLatitude: number): ShipmentTracking {
  return {
    motherVesselName: shipment.motherVessel,
    motherVessel: namedFix(motherName, motherLatitude),
    motherVesselNameWarning: motherWarning,
    feederVesselName: shipment.feederVessel,
    feederVessel: namedFix(feederName, feederLatitude),
    feederVesselNameWarning: feederWarning,
  };
}

const tracking: ShipmentTracking = {
  motherVesselName: shipment.motherVessel,
  motherVessel: shipment.motherVesselPosition,
  motherVesselNameWarning: null,
  feederVesselName: shipment.feederVessel,
  feederVessel: shipment.feederVesselPosition,
  feederVesselNameWarning: null,
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
    expect(within(record).getByText('Low')).toHaveClass('risk-badge', 'risk-low');
    expect(within(record).getByText(/At least 24 hours to transfer cargo between vessels\./)).toBeInTheDocument();
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
      motherVesselNameWarning: null,
      feederVesselName: shipment.feederVessel,
      feederVessel: null,
      feederVesselNameWarning: null,
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
      risk: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({ ...tracking, feederVesselName: null, feederVessel: null });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    const pending = within(record).getAllByText('Pending assignment');
    expect(pending).toHaveLength(3);
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
      motherVesselNameWarning: null,
      feederVesselName: shipment.feederVessel,
      feederVessel: null,
      feederVesselNameWarning: null,
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getAllByText('No live AIS position')).toHaveLength(2);
  });

  it('lets an importer edit all shipment fields and sends the hidden version token', async () => {
    const updated = { ...shipment, shipmentReference: 'HL-UPDATED', origin: 'Busan, KR', version: 4 };
    const refreshedTracking: ShipmentTracking = {
      motherVesselName: updated.motherVessel,
      motherVessel: { ...shipment.motherVesselPosition!, vesselName: 'NEW MOTHER POSITION', latitude: 1.31 },
      motherVesselNameWarning: null,
      feederVesselName: updated.feederVessel,
      feederVessel: null,
      feederVesselNameWarning: null,
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
    expect(await screen.findAllByText(/NEW MOTHER POSITION/)).not.toHaveLength(0);
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
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment not found or has been removed.');
  });

  it('shows an archived shipment edit rejection without marking the reference invalid', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('This shipment cannot be edited in its current status.', 403));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment cannot be edited in its current status.');
    expect(screen.getByLabelText('Shipment reference')).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
  });

  it.each([admin, manager])('shows Remove on an owned shipment opened directly by a $role', async account => {
    open(account, account.role === 'ADMIN' ? '/admin/shipments/7' : '/logistics-manager/shipments/7');
    expect(await screen.findByRole('button', { name: 'Remove HL-1001' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit shipment' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
  });

  it.each([admin, manager])('hides Remove when a $role opens an importer-linked shipment directly', async account => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, companyId: 11 });
    open(account, account.role === 'ADMIN' ? '/admin/shipments/7' : '/logistics-manager/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove HL-1001' })).not.toBeInTheDocument();
  });

  it('returns to the admin shipment list with the server message after removal', async () => {
    vi.mocked(archiveShipment).mockResolvedValue('Shipment removed successfully');
    open(admin, '/admin/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Remove HL-1001' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
    expect(archiveShipment).toHaveBeenCalledWith('admin-token', 7);
    expect(await screen.findByText('Shipment removed successfully')).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/shipments');
    expect(screen.getByRole('heading', { name: 'Company shipments' })).toBeInTheDocument();
  });
});

describe('vessel identity on the shipment detail', () => {
  it('shows Not available when IMO, MMSI, and operator were not stored', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('Mother vessel IMO').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Mother vessel MMSI').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Mother vessel operator').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Feeder vessel IMO').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Feeder vessel MMSI').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Feeder vessel operator').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getAllByText('Not available')).toHaveLength(6);
    expect(within(record).queryByText('The AIS vessel name does not match the stored mother vessel.')).not.toBeInTheDocument();
    expect(within(record).getByText('Ever Steady')).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
  });

  it('shows the stored identity and the AIS name warning from the detail', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      motherVesselImo: '9387421',
      motherVesselMmsi: '563000111',
      motherVesselOperator: 'Harbourline',
      feederVesselImo: '9411882',
      feederVesselMmsi: '565987654',
      feederVesselOperator: 'Strait Feeders',
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
      feederVesselNameWarning: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({
      ...tracking,
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
      feederVesselNameWarning: null,
    });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('9387421')).toBeInTheDocument();
    expect(within(record).getByText('563000111')).toBeInTheDocument();
    expect(within(record).getByText('Harbourline')).toBeInTheDocument();
    expect(within(record).getByText('9411882')).toBeInTheDocument();
    expect(within(record).getByText('565987654')).toBeInTheDocument();
    expect(within(record).getByText('Strait Feeders')).toBeInTheDocument();
    expect(within(record).getByText('The AIS vessel name does not match the stored mother vessel.')).toHaveClass('vessel-name-warning');
    expect(within(record).queryByText('The AIS vessel name does not match the stored feeder vessel.')).not.toBeInTheDocument();
    expect(within(record).getByText('Ever Steady')).toBeInTheDocument();
  });

  it('clears a detail name warning when loaded tracking returns an explicit null', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      motherVesselNameWarning: MOTHER_WARNING,
      feederVesselNameWarning: FEEDER_WARNING,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({
      ...tracking,
      motherVesselNameWarning: null,
      feederVesselNameWarning: null,
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(within(record).queryByText(FEEDER_WARNING)).not.toBeInTheDocument();
  });

  it('keeps the detail warning beside the recorded position when tracking cannot be loaded', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      motherVesselNameWarning: MOTHER_WARNING,
      feederVesselNameWarning: FEEDER_WARNING,
      feederVesselPosition: namedFix('STRAIT RUNNER', 2),
    });
    vi.mocked(getShipmentTracking).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('STRAIT RUNNER · 2, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
  });

  it('moves both vessel warnings with the position returned by refresh', async () => {
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracked('mv   pacific   horizon', null, 'STRAIT RUNNER', FEEDER_WARNING, 1.1, 2.2))
      .mockResolvedValueOnce(tracked('PACIFIC HORIZON', MOTHER_WARNING, 'mv   strait   runner', null, 1.4, 2.4))
      .mockResolvedValueOnce(tracked('mv   pacific   horizon', null, 'STRAIT RUNNER', FEEDER_WARNING, 1.1, 2.2));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(positionText(record, 'mv   pacific   horizon · 1.1, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(positionText(record, 'STRAIT RUNNER · 2.2, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await findPosition(record, 'PACIFIC HORIZON · 1.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(positionText(record, 'mv   strait   runner · 2.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).queryByText(FEEDER_WARNING)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await findPosition(record, 'mv   pacific   horizon · 1.1, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
    expect(getShipment).toHaveBeenCalledTimes(1);
    expect(getShipmentTracking).toHaveBeenCalledTimes(3);
  });

  it('applies the same warning transition when tracking is polled', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracked('mv   pacific   horizon', null, 'mv   strait   runner', null, 1.1, 2.2))
      .mockResolvedValueOnce(tracked('PACIFIC HORIZON', MOTHER_WARNING, 'STRAIT RUNNER', FEEDER_WARNING, 1.4, 2.4));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(within(record).queryByText(FEEDER_WARNING)).not.toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
    expect(await within(record).findByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText('PACIFIC HORIZON · 1.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('STRAIT RUNNER · 2.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    vi.useRealTimers();
  });

  it('keeps the current warnings when a refresh fails', async () => {
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracked('PACIFIC HORIZON', MOTHER_WARNING, 'STRAIT RUNNER', FEEDER_WARNING, 1.4, 2.4))
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText(MOTHER_WARNING)).toBeInTheDocument();
    expect(within(record).getByText(FEEDER_WARNING)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('The latest position could not be loaded. The map is still showing the last position it received.')).toBeInTheDocument();
    expect(within(record).getByText('PACIFIC HORIZON · 1.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('STRAIT RUNNER · 2.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
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

  it('accepts a detail response that omits arrivalComparison', async () => {
    const { arrivalComparison: _comparison, ...omitted } = shipment;
    await expect(decode(omitted)).resolves.toMatchObject({ arrivalComparison: null, connectionWindow: shipment.connectionWindow });
  });

  it('accepts an explicitly unassigned feeder vessel', async () => {
    await expect(decode({ ...shipment, feederVessel: null, plannedFeederDepartureAt: null }))
      .resolves.toMatchObject({ feederVessel: null, plannedFeederDepartureAt: null });
  });

  it('rejects an unknown risk level', async () => {
    await expect(decode({ ...shipment, risk: { level: 'SEVERE', explanation: 'Unknown.' } }))
      .rejects.toMatchObject({ status: 502 });
  });

  it('treats omitted vessel identity as absent and rejects a non-text value', async () => {
    await expect(decode(shipment)).resolves.toMatchObject({
      motherVesselImo: null,
      feederVesselOperator: null,
      motherVesselNameWarning: null,
    });
    await expect(decode({
      ...shipment,
      motherVesselImo: '9387421',
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
    })).resolves.toMatchObject({
      motherVesselImo: '9387421',
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
    });
    await expect(decode({ ...shipment, feederVesselMmsi: 565987654 })).rejects.toMatchObject({ status: 502 });
  });

  it('rejects a response that omits the feeder vessel fields', async () => {
    const { feederVessel: _vessel, plannedFeederDepartureAt: _departure, ...omitted } = shipment;
    await expect(decode(omitted)).rejects.toMatchObject({ status: 502 });
  });
});

const LIMITATION = 'Approximate arrival — based on straight-line distance and current speed.';

function availableComparison(classification: ArrivalClassification, difference: string, possiblyOutdated = false): ArrivalComparison {
  return {
    estimatedArrivalAt: '2026-10-02T01:30:00Z',
    limitation: LIMITATION,
    speedOverGroundKnots: 12.375,
    classification,
    difference,
    differenceSeconds: classification === 'AHEAD_OF_PLAN' ? -5400 : 5400,
    differenceNanos: 0,
    lastReceivedAt: '2026-10-01T18:00:00Z',
    possiblyOutdated,
    unavailableReason: null,
  };
}

describe('arrival comparison on the shipment detail', () => {
  async function recordFor(comparison: ArrivalComparison | null) {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, arrivalComparison: comparison });
    open(forwarder, '/freight-forwarder/shipments/7');
    return screen.findByRole('article', { name: 'Shipment HL-1001' });
  }

  it('keeps the planned arrival when the comparison is omitted', async () => {
    const record = await recordFor(null);
    expect(within(record).queryByText('Estimated arrival')).not.toBeInTheDocument();
    expect(within(record).queryByText(LIMITATION)).not.toBeInTheDocument();
    expect(within(record).queryByText('Possibly outdated')).not.toBeInTheDocument();
    expect(within(record).getByText(formatWhen(shipment.plannedMotherArrivalAt))).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(within(record).getByText('Low')).toHaveClass('risk-badge', 'risk-low');
  });

  it('shows an on-schedule gap beside the planned arrival', async () => {
    const comparison = availableComparison('ON_SCHEDULE', 'On schedule · estimated 1h 30m later than planned');
    const record = await recordFor(comparison);
    const planned = within(record).getByText('Planned arrival').parentElement;
    expect(planned).toContainElement(within(record).getByText('Estimated arrival'));
    expect(within(record).getByText(formatWhen(shipment.plannedMotherArrivalAt))).toBeInTheDocument();
    expect(within(record).getByText(formatWhen('2026-10-02T01:30:00Z'))).toBeInTheDocument();
    expect(within(record).getByText('On schedule · estimated 1h 30m later than planned')).toBeInTheDocument();
    expect(within(record).getByText(LIMITATION)).toBeInTheDocument();
    expect(within(record).getByText('12.375 knots')).toBeInTheDocument();
    expect(within(record).getByText('Last received')).toBeInTheDocument();
    expect(within(record).getByText(formatWhen('2026-10-01T18:00:00Z'))).toBeInTheDocument();
    expect(within(record).queryByText('Possibly outdated')).not.toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(within(record).getByText(/At least 24 hours to transfer cargo between vessels\./)).toBeInTheDocument();
  });

  it('shows an arrival ahead of plan', async () => {
    const record = await recordFor(availableComparison('AHEAD_OF_PLAN', 'Ahead of plan · estimated 1h 30m earlier than planned'));
    expect(within(record).getByText('Ahead of plan · estimated 1h 30m earlier than planned')).toBeInTheDocument();
  });

  it('shows an arrival behind plan', async () => {
    const record = await recordFor(availableComparison('BEHIND_PLAN', 'Behind plan · estimated 6h later than planned'));
    expect(within(record).getByText('Behind plan · estimated 6h later than planned')).toBeInTheDocument();
  });

  it('explains an unavailable comparison without an arrival time', async () => {
    const record = await recordFor({
      estimatedArrivalAt: null,
      limitation: null,
      speedOverGroundKnots: null,
      classification: null,
      difference: null,
      differenceSeconds: null,
      differenceNanos: null,
      lastReceivedAt: null,
      possiblyOutdated: null,
      unavailableReason: { code: 'NO_POSITION', message: 'No AIS position has been received for the mother vessel.' },
    });
    expect(within(record).getByText('No AIS position has been received for the mother vessel.')).toBeInTheDocument();
    expect(within(record).queryByText(LIMITATION)).not.toBeInTheDocument();
    expect(within(record).queryByText('12.375 knots')).not.toBeInTheDocument();
    expect(within(record).queryByText(formatWhen('2026-10-02T01:30:00Z'))).not.toBeInTheDocument();
    expect(within(record).getByText(formatWhen(shipment.plannedMotherArrivalAt))).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(within(record).getByText('Low')).toBeInTheDocument();
  });

  it('says when the retained position may be outdated', async () => {
    const record = await recordFor(availableComparison('ON_SCHEDULE', 'On schedule · estimated at the planned time', true));
    expect(within(record).getByText('Possibly outdated')).toBeInTheDocument();
    expect(within(record).getByText('On schedule · estimated at the planned time')).toBeInTheDocument();
    expect(within(record).getByText(formatWhen('2026-10-02T01:30:00Z'))).toBeInTheDocument();
  });
});
