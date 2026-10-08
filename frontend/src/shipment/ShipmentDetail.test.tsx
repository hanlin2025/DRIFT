import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { getShipment, getShipmentTracking, listImporterOrganisations, listShipments, type Shipment, type ShipmentTracking } from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('./api', () => ({
  createShipment: vi.fn(),
  listShipments: vi.fn(),
  getShipment: vi.fn(),
  getShipmentTracking: vi.fn(),
  listImporterOrganisations: vi.fn(),
  linkShipmentImporter: vi.fn(),
}));

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
  vi.mocked(loadSession).mockImplementation(async token => ({ ...account, token }));
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
  vi.mocked(listImporterOrganisations).mockResolvedValue([]);
  vi.mocked(getShipment).mockResolvedValue(shipment);
  vi.mocked(getShipmentTracking).mockResolvedValue(tracking);
});

describe('connection window on the shipment detail', () => {
  it('opens the detail from the reference and shows the planned window', async () => {
    open(forwarder, '/freight-forwarder');
    const user = userEvent.setup();
    const link = await screen.findByRole('link', { name: 'HL-1001' });
    expect(link).toHaveAttribute('href', '/freight-forwarder/shipments/7');
    expect(screen.queryByText('1 day 10 hours')).not.toBeInTheDocument();
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
});
