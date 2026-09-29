import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { createShipment, getShipment, listShipments, type Shipment } from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('./api', () => ({ createShipment: vi.fn(), listShipments: vi.fn(), getShipment: vi.fn() }));

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
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
  connectionWindow: { duration: '1 day 10 hours', totalSeconds: 122400 },
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function open(account: Session, path: string) {
  writeSession(account);
  vi.mocked(loadSession).mockImplementation(async token => ({ ...account, token }));
  return render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter>);
}

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(listShipments).mockResolvedValue([shipment]);
  vi.mocked(getShipment).mockResolvedValue(shipment);
});

describe('company shipment list', () => {
  it("shows the importer's company shipments with their route and vessels", async () => {
    open(importer, '/importer');
    const row = await screen.findByRole('link', { name: /HL-1001/ });
    expect(within(row).getByText('Singapore to Jakarta')).toBeInTheDocument();
    expect(within(row).getByText('Ever Steady')).toBeInTheDocument();
    expect(within(row).getByText('Straits Feeder')).toBeInTheDocument();
    expect(within(row).getByText(/Arrives/)).toBeInTheDocument();
    expect(within(row).queryByText('1 day 10 hours')).not.toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledWith('importer-token');
  });

  it('offers an empty list, with registration only where the form is available', async () => {
    vi.mocked(listShipments).mockResolvedValue([]);
    const view = open(importer, '/importer');
    expect(await screen.findByText('No shipments yet')).toBeInTheDocument();
    expect(screen.getByText('Shipments registered for your company will appear here.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Register a shipment' })).not.toBeInTheDocument();
    view.unmount();

    open(forwarder, '/freight-forwarder');
    expect(await screen.findByText('No shipments yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register a shipment' })).toHaveAttribute('href', '#register-shipment');
  });

  it('reloads the list after a shipment is registered', async () => {
    vi.mocked(listShipments).mockResolvedValueOnce([]);
    vi.mocked(createShipment).mockResolvedValue(shipment);
    open(forwarder, '/freight-forwarder');
    expect(await screen.findByText('No shipments yet')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-1001');
    await user.type(screen.getByLabelText('Origin'), 'Singapore');
    await user.type(screen.getByLabelText('Destination'), 'Jakarta');
    await user.type(screen.getByLabelText('Mother vessel'), 'Ever Steady');
    await user.type(screen.getByLabelText('Feeder vessel'), 'Straits Feeder');
    fireEvent.change(screen.getByLabelText('Planned mother-vessel arrival'), { target: { value: '2026-10-02T08:00' } });
    fireEvent.change(screen.getByLabelText('Planned feeder-vessel departure'), { target: { value: '2026-10-03T18:00' } });
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));

    expect(await screen.findByRole('link', { name: /HL-1001/ })).toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledTimes(2);
  });

  it('loads the list again after the page is reopened', async () => {
    const view = open(importer, '/importer');
    expect(await screen.findByRole('link', { name: /HL-1001/ })).toBeInTheDocument();
    view.unmount();

    render(<MemoryRouter initialEntries={['/importer']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: /HL-1001/ })).toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledTimes(2);
  });

  it('shows a load failure and tries again', async () => {
    vi.mocked(listShipments).mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    open(importer, '/importer');
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT.');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('link', { name: /HL-1001/ })).toBeInTheDocument();
  });

  it('ends the session when the list is rejected', async () => {
    vi.mocked(listShipments).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    open(importer, '/importer');
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });
});

describe('shipment details', () => {
  it('opens a selected shipment and returns to the list', async () => {
    open(importer, '/importer');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: /HL-1001/ }));
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/importer/shipments/7');
    expect(getShipment).toHaveBeenCalledWith('importer-token', '7');
    const record = screen.getByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('Ever Steady')).toBeInTheDocument();
    expect(within(record).getByText('Planned departure')).toBeInTheDocument();
    expect(within(record).getByText('Connection window')).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: /All shipments/ }));
    expect(screen.getByTestId('location')).toHaveTextContent('/importer');
    expect(await screen.findByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
  });

  it('shows when the shipment has no planned connection window', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, connectionWindow: null });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('Connection window')).toBeInTheDocument();
    expect(within(record).getByText('No planned connection window')).toBeInTheDocument();
  });

  it('explains when a shipment is not available to the company', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(importer, '/importer/shipments/99');
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment not found');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /All shipments/ })).toHaveAttribute('href', '/importer');
  });

  it("sends a shipment link to the signed-in role's own workspace", async () => {
    open(importer, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/importer');
  });
});
