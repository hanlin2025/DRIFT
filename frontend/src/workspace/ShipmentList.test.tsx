import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { listShipments, type Shipment } from '../shipment/api';
import { ApiError } from '../signup/api';
import { ShipmentList } from './ShipmentList';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ listShipments: vi.fn(), createShipment: vi.fn() }));

const newer: Shipment = {
  id: 2,
  shipmentReference: 'HBL-NEWER',
  origin: 'Shanghai, CN',
  destination: 'Jakarta, ID',
  transshipmentPort: 'Tanjung Pelepas',
  motherVessel: 'MV Pacific Horizon',
  plannedMotherArrivalAt: '2026-10-04T00:00:00Z',
  feederVessel: 'MV Strait Runner',
  plannedFeederDepartureAt: '2026-10-05T00:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
};
const older: Shipment = {
  id: 1,
  shipmentReference: 'HBL-OLDER',
  origin: 'Busan, KR',
  destination: 'Singapore, SG',
  transshipmentPort: 'Singapore',
  motherVessel: 'MV Northern Light',
  plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
  feederVessel: 'MV Harbour Link',
  plannedFeederDepartureAt: '2026-10-03T00:00:00Z',
  createdAt: '2026-09-28T04:00:00Z',
};

const account = (role: Session['role']): Session => ({
  token: 'session-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 4,
  fullName: 'Alice Tan',
  email: 'alice@example.com',
  role,
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
});

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(loadSession).mockImplementation(async token => ({ ...account('FREIGHT_FORWARDER'), token }));
  vi.mocked(listShipments).mockResolvedValue([newer, older]);
});

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function openWorkspace(role: Session['role'] = 'FREIGHT_FORWARDER', path?: string) {
  const current = account(role);
  writeSession(current);
  vi.mocked(loadSession).mockImplementation(async token => ({ ...current, token }));
  const entry = path ?? (role === 'IMPORTER' ? '/importer' : '/freight-forwarder');
  render(<MemoryRouter initialEntries={[entry]}><AppRoutes /><Location /></MemoryRouter>);
}

describe('shipment list', () => {
  it('maps each shipment field to its column and keeps the supplied order', () => {
    render(<ShipmentList shipments={[newer, older]} />);
    expect(screen.getAllByRole('columnheader').map(header => header.textContent)).toEqual([
      'Reference', 'Origin', 'Destination', 'Transshipment port', 'Mother vessel', 'Feeder vessel',
    ]);
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'HBL-NEWER', 'Shanghai, CN', 'Jakarta, ID', 'Tanjung Pelepas', 'MV Pacific Horizon', 'MV Strait Runner',
    ]);
    expect(within(rows[1]).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'HBL-OLDER', 'Busan, KR', 'Singapore, SG', 'Singapore', 'MV Northern Light', 'MV Harbour Link',
    ]);
  });

  it('renders no shipment rows for an empty list', () => {
    render(<ShipmentList shipments={[]} />);
    expect(screen.getAllByRole('columnheader')).toHaveLength(6);
    expect(screen.queryAllByRole('cell')).toHaveLength(0);
  });
});

describe('workspace shipment retrieval', () => {
  it('shows the shipments returned by the API in that order, including the transshipment port', async () => {
    vi.mocked(listShipments).mockResolvedValue([older, newer]);
    openWorkspace();
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    const rows = (await screen.findAllByRole('row')).slice(1);
    expect(within(rows[0]).getByRole('cell', { name: 'HBL-OLDER' })).toBeInTheDocument();
    expect(within(rows[0]).getByRole('cell', { name: 'Singapore' })).toBeInTheDocument();
    expect(within(rows[1]).getByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(within(rows[1]).getByRole('cell', { name: 'Tanjung Pelepas' })).toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledWith('session-token');
    expect(within(rows[0]).getByRole('link', { name: 'HBL-OLDER' })).toHaveAttribute('href', '/freight-forwarder/shipments/1');
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(within(rows[0]).getByRole('link', { name: 'HBL-OLDER' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/shipments/1');
    const route = await screen.findByRole('figure', { name: 'Planned route' });
    expect(within(route).getByText('DEPARTURE Busan, KR')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Back' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
  });

  it('opens a shipment address directly and explains a missing shipment', async () => {
    openWorkspace('FREIGHT_FORWARDER', '/freight-forwarder/shipments/2');
    expect(await screen.findByRole('heading', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/shipments/2');
    expect(within(screen.getByRole('figure', { name: 'Planned route' })).getByText('DEPARTURE Shanghai, CN')).toBeInTheDocument();
    const details = screen.getByRole('article', { name: 'Shipment HBL-NEWER' });
    expect(within(details).getByText('Shanghai, CN')).toBeInTheDocument();
    expect(within(details).getByText('Tanjung Pelepas')).toBeInTheDocument();
    expect(within(details).getByText('Jakarta, ID')).toBeInTheDocument();
    expect(within(details).getByText('MV Pacific Horizon')).toBeInTheDocument();
    expect(within(details).getByText('MV Strait Runner')).toBeInTheDocument();
    expect(within(details).getByText('Planned arrival')).toBeInTheDocument();
    expect(within(details).getByText('Planned departure')).toBeInTheDocument();
    expect(within(details).getByText('Registered')).toBeInTheDocument();

    cleanup();
    openWorkspace('FREIGHT_FORWARDER', '/freight-forwarder/shipments/99');
    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment is not available.');
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/freight-forwarder');
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();
  });

  it('shows a loading state until the list arrives', async () => {
    let resolveList: (rows: Shipment[]) => void = () => {};
    vi.mocked(listShipments).mockImplementation(() => new Promise(resolve => { resolveList = resolve; }));
    openWorkspace();
    expect(await screen.findByText('Loading shipments...')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    resolveList([newer]);
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.queryByText('Loading shipments...')).not.toBeInTheDocument();
  });

  it('retries a failed list request', async () => {
    vi.mocked(listShipments)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce([newer]);
    openWorkspace();
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT. Check your connection and try again.');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledTimes(2);
  });

  it('shows an empty company after a successful empty response', async () => {
    vi.mocked(listShipments).mockResolvedValue([]);
    openWorkspace();
    expect(await screen.findByText('No shipments yet. Shipments registered for your company will appear here.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
  });

  it('ends the session and returns to login when the list request is rejected', async () => {
    vi.mocked(listShipments).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    openWorkspace();
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows the same shipments to an importer without the registration form', async () => {
    openWorkspace('IMPORTER');
    expect(await screen.findByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'HBL-NEWER' })).toHaveAttribute('href', '/importer/shipments/2');
    expect(screen.getByRole('cell', { name: 'Tanjung Pelepas' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a shipment' })).not.toBeInTheDocument();
    cleanup();

    openWorkspace();
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
  });

  it('does not apply a list response after the workspace unmounts', async () => {
    let resolveList: (rows: Shipment[]) => void = () => {};
    vi.mocked(listShipments).mockImplementation(() => new Promise(resolve => { resolveList = resolve; }));
    openWorkspace();
    expect(await screen.findByText('Loading shipments...')).toBeInTheDocument();
    cleanup();
    resolveList([newer]);
    await Promise.resolve();
    expect(screen.queryByRole('cell', { name: 'HBL-NEWER' })).not.toBeInTheDocument();
  });
});
