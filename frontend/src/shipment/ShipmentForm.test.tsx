import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { createShipment, listShipments, type Shipment } from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('./api', () => ({ createShipment: vi.fn(), listShipments: vi.fn() }));

const forwarder: Session = {
  token: 'session-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 4,
  fullName: 'Alice Tan',
  email: 'alice@example.com',
  role: 'FREIGHT_FORWARDER',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
};

const importer: Session = {
  ...forwarder,
  id: 8,
  role: 'IMPORTER',
  company: { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' },
};

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(loadSession).mockImplementation(async token => ({ ...forwarder, token }));
  vi.mocked(listShipments).mockResolvedValue([]);
  vi.mocked(createShipment).mockImplementation(async (_token, shipment) => ({
    id: 9,
    createdAt: '2026-09-29T04:00:00Z',
    ...shipment,
  }));
});

async function openPortfolio() {
  writeSession(forwarder);
  render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
  expect(await screen.findByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
}

async function fillItinerary(departure = '2026-10-03T18:00') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Shipment reference'), ' HL-1001 ');
  await user.type(screen.getByLabelText('Origin'), 'Singapore');
  await user.type(screen.getByLabelText('Destination'), 'Jakarta');
  await user.type(screen.getByLabelText('Transshipment port'), 'Tanjung Pelepas');
  await user.type(screen.getByLabelText('Mother vessel'), 'Ever Steady');
  await user.type(screen.getByLabelText('Feeder vessel'), 'Straits Feeder');
  fireEvent.change(screen.getByLabelText('Planned mother-vessel arrival'), { target: { value: '2026-10-02T08:00' } });
  fireEvent.change(screen.getByLabelText('Planned feeder-vessel departure'), { target: { value: departure } });
  return user;
}

const listed: Shipment = {
  id: 2,
  shipmentReference: 'HBL-NEWER',
  origin: 'Shanghai, CN',
  destination: 'Jakarta, ID',
  transshipmentPort: 'Singapore',
  motherVessel: 'MV Pacific Horizon',
  plannedMotherArrivalAt: '2026-10-04T00:00:00Z',
  feederVessel: 'MV Strait Runner',
  plannedFeederDepartureAt: '2026-10-05T00:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
};

describe('shipment registration', () => {
  it('offers registration on the freight-forwarder portfolio and not on the importer overview', async () => {
    vi.mocked(listShipments).mockResolvedValue([listed]);
    writeSession(importer);
    vi.mocked(loadSession).mockImplementation(async token => ({ ...importer, token }));
    render(<MemoryRouter initialEntries={['/importer']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Singapore' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a shipment' })).not.toBeInTheDocument();
    cleanup();

    writeSession(forwarder);
    vi.mocked(loadSession).mockImplementation(async token => ({ ...forwarder, token }));
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByText('This shipment is registered for Harbourline Logistics (Demo).')).toBeInTheDocument();
  });

  it('checks the itinerary before calling the API', async () => {
    await openPortfolio();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(screen.getByText('Shipment reference is required')).toBeInTheDocument();
    expect(screen.getByText('Planned mother-vessel arrival is required')).toBeInTheDocument();
    expect(createShipment).not.toHaveBeenCalled();

    await fillItinerary('2026-10-01T08:00');
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(screen.getByText('Planned feeder-vessel departure must be after planned mother-vessel arrival')).toBeInTheDocument();
    expect(createShipment).not.toHaveBeenCalled();
  });

  it('registers the shipment with the signed-in session and shows the saved itinerary', async () => {
    await openPortfolio();
    const user = await fillItinerary();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('HL-1001');
    expect(screen.getByText('Singapore to Jakarta')).toBeInTheDocument();
    expect(screen.getByText('Tanjung Pelepas')).toBeInTheDocument();
    expect(screen.getByText('Ever Steady')).toBeInTheDocument();
    expect(screen.getByText('Straits Feeder')).toBeInTheDocument();
    expect(createShipment).toHaveBeenCalledTimes(1);
    expect(createShipment).toHaveBeenCalledWith('session-token', expect.objectContaining({
      shipmentReference: 'HL-1001',
      origin: 'Singapore',
      destination: 'Jakarta',
      transshipmentPort: 'Tanjung Pelepas',
      motherVessel: 'Ever Steady',
      feederVessel: 'Straits Feeder',
    }));
    const body = vi.mocked(createShipment).mock.calls[0][1];
    expect(Date.parse(body.plannedFeederDepartureAt)).toBeGreaterThan(Date.parse(body.plannedMotherArrivalAt));
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('');
    expect(listShipments).toHaveBeenCalledWith('session-token');
  });

  it('reloads the company list after the shipment is registered', async () => {
    vi.mocked(listShipments).mockResolvedValueOnce([]).mockResolvedValue([listed]);
    await openPortfolio();
    const user = await fillItinerary();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(createShipment).toHaveBeenCalledTimes(1);
    expect(listShipments).toHaveBeenCalledTimes(2);
    expect(listShipments).toHaveBeenNthCalledWith(2, 'session-token');
  });

  it('replaces the saved shipment when the next registration fails', async () => {
    await openPortfolio();
    const user = await fillItinerary();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('HL-1001');
    vi.mocked(createShipment).mockRejectedValueOnce(new ApiError('A shipment with this reference already exists for your company', 409));
    await fillItinerary();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A shipment with this reference already exists for your company');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(sessionStorage.getItem('drift.session')).toContain('session-token');
  });

  it('shows a duplicate reference on the field and keeps the session', async () => {
    vi.mocked(createShipment).mockRejectedValue(new ApiError('A shipment with this reference already exists for your company', 409));
    await openPortfolio();
    const user = await fillItinerary();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A shipment with this reference already exists for your company');
    expect(screen.getByLabelText('Shipment reference')).toHaveAttribute('aria-invalid', 'true');
    expect(sessionStorage.getItem('drift.session')).toContain('session-token');
  });

  it('shows a field error returned by the API', async () => {
    vi.mocked(createShipment).mockRejectedValue(new ApiError('Shipment information is missing or invalid', 400, {
      origin: 'Origin is required',
    }));
    await openPortfolio();
    const user = await fillItinerary();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment information is missing or invalid');
    expect(screen.getByText('Origin is required')).toBeInTheDocument();
  });

  it('ends the session when the API rejects the token', async () => {
    vi.mocked(createShipment).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    await openPortfolio();
    const user = await fillItinerary();
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(screen.getByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });
});
