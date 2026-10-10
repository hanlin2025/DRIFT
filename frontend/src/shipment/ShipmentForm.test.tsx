import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { ShipmentForm } from './ShipmentForm';
import { createShipment, linkShipmentImporter, listShipments, searchImporterOrganisations, updateShipment, type ImporterOrganisation, type Shipment } from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({
  createShipment: vi.fn(),
  updateShipment: vi.fn(),
  listShipments: vi.fn(),
  searchImporterOrganisations: vi.fn(),
  linkShipmentImporter: vi.fn(),
}));

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

const manager: Session = {
  ...forwarder,
  id: 6,
  token: 'manager-token',
  role: 'LOGISTICS_MANAGER',
};

const straits: ImporterOrganisation = { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' };

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(loadSession).mockImplementation(async token => ({ ...forwarder, token }));
  vi.mocked(listShipments).mockResolvedValue([]);
  vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
  vi.mocked(createShipment).mockImplementation(async (_token, shipment) => ({
    id: 9,
    createdAt: '2026-09-29T04:00:00Z',
    version: 0,
    ...shipment,
    connectionWindow: { duration: '1 day 4 hours', totalSeconds: 100800 },
    risk: null,
    motherVesselPosition: null,
    feederVesselPosition: null,
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
  version: 0,
  connectionWindow: null,
  risk: null,
  motherVesselPosition: null,
  feederVesselPosition: null,
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
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
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

describe('importer linking', () => {
  it('shows the importer search to a freight forwarder and hides it from other roles', async () => {
    await openPortfolio();
    expect(screen.getByLabelText('Link importer')).toBeInTheDocument();
    cleanup();

    writeSession(manager);
    vi.mocked(loadSession).mockImplementation(async token => ({ ...manager, token }));
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Link importer')).not.toBeInTheDocument();
    cleanup();

    writeSession(importer);
    render(<ShipmentForm token={importer.token} shipment={listed} onSessionEnded={vi.fn()} onUpdated={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Link importer')).not.toBeInTheDocument();
  });

  it('loads matching organisations as the freight forwarder types', async () => {
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    await openPortfolio();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Link importer'), 'Straits');
    expect(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ })).toBeInTheDocument();
    await waitFor(() => expect(searchImporterOrganisations).toHaveBeenCalledWith('session-token', 'Straits'));
  });

  it('chooses the highlighted organisation with Enter and does not register the shipment', async () => {
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    await openPortfolio();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Link importer'), 'Straits');
    expect(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ })).toBeInTheDocument();
    await user.keyboard('{Enter}');
    expect(screen.getByLabelText('Link importer')).toHaveValue('Straits Fresh Imports (Demo)');
    expect(createShipment).not.toHaveBeenCalled();
  });

  it('asks for a listed organisation before registering free text', async () => {
    await openPortfolio();
    const user = await fillItinerary();
    await user.type(screen.getByLabelText('Link importer'), 'Not a company');
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByText('Select an importer organisation from the list.')).toBeInTheDocument();
    expect(createShipment).not.toHaveBeenCalled();
    expect(linkShipmentImporter).not.toHaveBeenCalled();
  });

  it('links the new shipment to the selected organisation', async () => {
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    vi.mocked(linkShipmentImporter).mockImplementation(async (_token, id, importerCompanyId) => ({
      id,
      shipmentReference: 'HL-1001',
      origin: 'Singapore',
      destination: 'Jakarta',
      transshipmentPort: 'Tanjung Pelepas',
      motherVessel: 'Ever Steady',
      plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
      feederVessel: 'Straits Feeder',
      plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
      createdAt: '2026-09-29T04:00:00Z',
      version: 1,
      connectionWindow: null,
      motherVesselPosition: null,
      feederVesselPosition: null,
      importer: importerCompanyId == null ? null : straits,
    }));
    await openPortfolio();
    const user = await fillItinerary();
    await user.type(screen.getByLabelText('Link importer'), 'Straits');
    await user.click(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ }));
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByText('Shipment linked to Straits Fresh Imports (Demo)')).toBeInTheDocument();
    expect(createShipment).toHaveBeenCalledTimes(1);
    expect(linkShipmentImporter).toHaveBeenCalledWith('session-token', 9, 2);
    expect(vi.mocked(createShipment).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(linkShipmentImporter).mock.invocationCallOrder[0]);
    expect(screen.getByRole('status', { name: 'Registered shipment HL-1001' })).toHaveTextContent('Straits Fresh Imports (Demo)');
  });

  it('keeps the registered shipment and retries the link after an invalid organisation', async () => {
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    vi.mocked(linkShipmentImporter)
      .mockRejectedValueOnce(new ApiError('Invalid or inactive importer organisation selected.', 400))
      .mockImplementation(async (_token, id) => ({
        id,
        shipmentReference: 'HL-1001',
        origin: 'Singapore',
        destination: 'Jakarta',
        transshipmentPort: 'Tanjung Pelepas',
        motherVessel: 'Ever Steady',
        plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
        feederVessel: 'Straits Feeder',
        plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
        createdAt: '2026-09-29T04:00:00Z',
        version: 1,
        connectionWindow: null,
        motherVesselPosition: null,
        feederVesselPosition: null,
        importer: straits,
      }));
    await openPortfolio();
    const user = await fillItinerary();
    await user.type(screen.getByLabelText('Link importer'), 'Straits');
    await user.click(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ }));
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid or inactive importer organisation selected.');
    expect(screen.getByLabelText('Link importer')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('status', { name: 'Registered shipment HL-1001' })).toBeInTheDocument();
    expect(createShipment).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: /Link shipment/ }));
    expect(await screen.findByText('Shipment linked to Straits Fresh Imports (Demo)')).toBeInTheDocument();
    expect(createShipment).toHaveBeenCalledTimes(1);
    expect(linkShipmentImporter).toHaveBeenCalledTimes(2);
    expect(sessionStorage.getItem('drift.session')).toContain('session-token');
  });

  it('leaves the registered shipment in place when the importer is cleared after a rejected link', async () => {
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    vi.mocked(linkShipmentImporter).mockRejectedValue(new ApiError('Invalid or inactive importer organisation selected.', 400));
    await openPortfolio();
    const user = await fillItinerary();
    await user.type(screen.getByLabelText('Link importer'), 'Straits');
    await user.click(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ }));
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid or inactive importer organisation selected.');
    await user.click(screen.getByRole('button', { name: 'Remove link' }));
    expect(screen.getByRole('button', { name: /Register shipment/ })).toBeEnabled();
    expect(screen.getByLabelText('Shipment reference')).toBeEnabled();
    expect(screen.getByRole('status', { name: 'Registered shipment HL-1001' })).toBeInTheDocument();
    expect(linkShipmentImporter).toHaveBeenCalledTimes(1);
  });

  it('shows a forbidden link without leaving the portfolio', async () => {
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    vi.mocked(linkShipmentImporter).mockRejectedValue(new ApiError('Only a freight forwarder in the shipment\'s company can link it to an importer', 403));
    await openPortfolio();
    const user = await fillItinerary();
    await user.type(screen.getByLabelText('Link importer'), 'Straits');
    await user.click(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ }));
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Only a freight forwarder in the shipment\'s company can link it to an importer');
    expect(screen.getByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
    expect(sessionStorage.getItem('drift.session')).toContain('session-token');
  });

  it('ends the session when linking is rejected as signed out', async () => {
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    vi.mocked(linkShipmentImporter).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    await openPortfolio();
    const user = await fillItinerary();
    await user.type(screen.getByLabelText('Link importer'), 'Straits');
    await user.click(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ }));
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });

  it('lets the freight forwarder try the organisation search again', async () => {
    vi.mocked(searchImporterOrganisations)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
    await openPortfolio();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Link importer'));
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT. Check your connection and try again.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('option', { name: /Straits Fresh Imports \(Demo\)/ })).toBeInTheDocument();
  });

  it('says when no organisation matches', async () => {
    await openPortfolio();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Link importer'), 'Missing');
    expect(await screen.findByText('No matching organisations.')).toBeInTheDocument();
    await waitFor(() => expect(searchImporterOrganisations).toHaveBeenCalledWith('session-token', 'Missing'));
  });

  it('saves an edit without calling the link endpoint when the importer is unchanged', async () => {
    const onUpdated = vi.fn();
    writeSession(forwarder);
    render(<ShipmentForm token={forwarder.token} shipment={{ ...listed, importer: straits }} onSessionEnded={vi.fn()} onUpdated={onUpdated} onCancel={vi.fn()} />);
    vi.mocked(updateShipment).mockResolvedValue({ ...listed, importer: straits, version: 1 });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /Save shipment/ }));
    await waitFor(() => expect(onUpdated).toHaveBeenCalled());
    expect(linkShipmentImporter).not.toHaveBeenCalled();
  });

  it('unlinks an importer from the edit form and returns the saved shipment', async () => {
    const onUpdated = vi.fn();
    writeSession(forwarder);
    const current = { ...listed, importer: straits };
    render(<ShipmentForm token={forwarder.token} shipment={current} onSessionEnded={vi.fn()} onUpdated={onUpdated} onCancel={vi.fn()} />);
    vi.mocked(updateShipment).mockResolvedValue({ ...current, version: 1 });
    vi.mocked(linkShipmentImporter).mockResolvedValue({ ...current, importer: null, version: 2 });
    const user = userEvent.setup();
    expect(screen.getByLabelText('Link importer')).toHaveValue('Straits Fresh Imports (Demo)');
    await user.click(screen.getByRole('button', { name: 'Remove link' }));
    await user.click(screen.getByRole('button', { name: /Save shipment/ }));
    expect(await screen.findByText('Shipment unlinked.')).toBeInTheDocument();
    expect(updateShipment).toHaveBeenCalledWith('session-token', 2, expect.objectContaining({ version: 0 }));
    expect(linkShipmentImporter).toHaveBeenCalledWith('session-token', 2, null);
    expect(onUpdated).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Back to shipment' }));
    expect(onUpdated).toHaveBeenCalledWith(expect.objectContaining({ importer: null, version: 2 }));
  });

  it('replaces the linked importer from the edit form', async () => {
    const harbour: ImporterOrganisation = { id: 5, code: 'NORTH_STAR', name: 'North Star Imports' };
    const onUpdated = vi.fn();
    writeSession(forwarder);
    render(<ShipmentForm token={forwarder.token} shipment={{ ...listed, importer: straits }} onSessionEnded={vi.fn()} onUpdated={onUpdated} onCancel={vi.fn()} />);
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [harbour], page: 0, size: 20, total: 1 });
    vi.mocked(updateShipment).mockResolvedValue({ ...listed, importer: straits, version: 1 });
    vi.mocked(linkShipmentImporter).mockResolvedValue({ ...listed, importer: harbour, version: 2 });
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText('Link importer'));
    await user.type(screen.getByLabelText('Link importer'), 'North');
    await user.click(await screen.findByRole('option', { name: /North Star Imports/ }));
    await user.click(screen.getByRole('button', { name: /Save shipment/ }));
    expect(await screen.findByText('Shipment linked to North Star Imports')).toBeInTheDocument();
    expect(linkShipmentImporter).toHaveBeenCalledWith('session-token', 2, 5);
    expect(onUpdated).not.toHaveBeenCalled();
  });
});
