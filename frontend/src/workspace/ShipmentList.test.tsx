import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { archiveShipment, getShipment, getShipmentTracking, listShipments, type Shipment } from '../shipment/api';
import { formatWhen } from '../shipment/ShipmentForm';
import { ApiError } from '../signup/api';
import { ShipmentList } from './ShipmentList';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ listShipments: vi.fn(), createShipment: vi.fn(), updateShipment: vi.fn(), getShipment: vi.fn(), getShipmentTracking: vi.fn(), archiveShipment: vi.fn() }));

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
  version: 0,
  connectionWindow: { duration: '1 day', totalSeconds: 86400 },
  companyId: 1,
  motherVesselPosition: null,
  feederVesselPosition: null,
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
  version: 0,
  connectionWindow: null,
  companyId: 1,
  motherVesselPosition: null,
  feederVesselPosition: null,
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
  vi.mocked(getShipment).mockImplementation(async (_token, id) => {
    const found = [older, newer].find(row => String(row.id) === id);
    if (!found) throw new ApiError('Shipment not found', 404);
    return found;
  });
  vi.mocked(getShipmentTracking).mockImplementation(async (_token, id) => {
    const found = [older, newer].find(row => String(row.id) === id);
    return {
      motherVesselName: found?.motherVessel ?? '',
      motherVessel: null,
      feederVesselName: found?.feederVessel ?? '',
      feederVessel: null,
    };
  });
});

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function workspacePath(role: Session['role']) {
  if (role === 'ADMIN') return '/admin/shipments';
  if (role === 'LOGISTICS_MANAGER') return '/logistics-manager';
  if (role === 'IMPORTER') return '/importer';
  return '/freight-forwarder';
}

function openWorkspace(role: Session['role'] = 'FREIGHT_FORWARDER', path?: string) {
  const current = account(role);
  writeSession(current);
  vi.mocked(loadSession).mockImplementation(async token => ({ ...current, token }));
  render(<MemoryRouter initialEntries={[path ?? workspacePath(role)]}><AppRoutes /><Location /></MemoryRouter>);
}

describe('shipment list', () => {
  it('maps each shipment field to its column and keeps the supplied order', () => {
    render(<MemoryRouter><ShipmentList shipments={[newer, older]} basePath="/freight-forwarder" /></MemoryRouter>);
    expect(screen.getAllByRole('columnheader').map(header => header.textContent)).toEqual([
      'Reference', 'Origin', 'Destination', 'Transshipment port', 'Mother vessel', 'Feeder vessel', 'Planned arrival',
    ]);
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'HBL-NEWER', 'Shanghai, CN', 'Jakarta, ID', 'Tanjung Pelepas', 'MV Pacific Horizon', 'MV Strait Runner', formatWhen('2026-10-04T00:00:00Z'),
    ]);
    expect(within(rows[1]).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'HBL-OLDER', 'Busan, KR', 'Singapore, SG', 'Singapore', 'MV Northern Light', 'MV Harbour Link', formatWhen('2026-10-02T00:00:00Z'),
    ]);
    expect(rows[0].querySelector('time')).toHaveAttribute('datetime', '2026-10-04T00:00:00Z');
    expect(screen.getByRole('link', { name: 'HBL-NEWER' })).toHaveAttribute('href', '/freight-forwarder/shipments/2');
    expect(screen.queryByText('1 day')).not.toBeInTheDocument();
    expect(screen.queryByText('Connection window')).not.toBeInTheDocument();
  });

  it('renders no shipment rows for an empty list', () => {
    render(<MemoryRouter><ShipmentList shipments={[]} basePath="/importer" /></MemoryRouter>);
    expect(screen.getAllByRole('columnheader')).toHaveLength(7);
    expect(screen.queryAllByRole('cell')).toHaveLength(0);
  });

  it('shows an unassigned feeder vessel as pending', () => {
    render(<MemoryRouter><ShipmentList shipments={[{ ...newer, feederVessel: null, plannedFeederDepartureAt: null }]} basePath="/importer" /></MemoryRouter>);
    expect(screen.getByRole('cell', { name: 'Pending assignment' })).toHaveClass('is-missing');
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
    expect(screen.getByRole('heading', { name: 'HBL-OLDER' })).toBeInTheDocument();
    expect(screen.getByText('No planned connection window')).toBeInTheDocument();
    expect(getShipment).toHaveBeenCalledWith('session-token', '1');
    await user.click(screen.getByRole('link', { name: 'Back' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    await user.click(await screen.findByRole('link', { name: 'HBL-NEWER' }));
    expect(within(await screen.findByRole('figure', { name: 'Planned route' })).getByText('DEPARTURE Shanghai, CN')).toBeInTheDocument();
    expect(screen.getByText('1 day')).toBeInTheDocument();
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
    expect(within(details).getByText('Connection window')).toBeInTheDocument();
    expect(within(details).getByText('1 day')).toBeInTheDocument();
    expect(within(details).getByText('Registered')).toBeInTheDocument();
    expect(within(details).getAllByRole('term').map(term => term.textContent)).toEqual([
      'Origin', 'Mother vessel', 'Mother vessel position', 'Planned arrival', 'Transshipment port', 'Feeder vessel', 'Feeder vessel position', 'Planned departure', 'Destination', 'Connection window', 'Registered',
    ]);

    cleanup();
    openWorkspace('FREIGHT_FORWARDER', '/freight-forwarder/shipments/99');
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment not found or has been removed.');
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/freight-forwarder');
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();
  });

  it('shows a blank transshipment port as not recorded', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...newer, transshipmentPort: '  ' });
    openWorkspace('FREIGHT_FORWARDER', '/freight-forwarder/shipments/2');
    const details = await screen.findByRole('article', { name: 'Shipment HBL-NEWER' });
    expect(within(details).getByText('Not recorded')).toHaveClass('is-missing');
    expect(screen.getByText('TRANSSHIPMENT Not recorded')).toBeInTheDocument();
  });

  it('sends a shipment opened on the other role to that role\'s own address', async () => {
    openWorkspace('FREIGHT_FORWARDER', '/importer/shipments/2');
    expect(await screen.findByRole('heading', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/shipments/2');
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/freight-forwarder');
  });

  it('returns an importer from a shipment to the importer overview', async () => {
    openWorkspace('IMPORTER', '/importer/shipments/2');
    expect(await screen.findByRole('heading', { name: 'HBL-NEWER' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('link', { name: 'Back' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/importer');
    expect(await screen.findByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a shipment' })).not.toBeInTheDocument();
  });

  it('retries a shipment page that could not be loaded', async () => {
    vi.mocked(getShipment)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce(newer);
    openWorkspace('FREIGHT_FORWARDER', '/freight-forwarder/shipments/2');
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT. Check your connection and try again.');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(getShipment).toHaveBeenCalledTimes(2);
  });

  it('ends the session when a shipment page is rejected', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    openWorkspace('FREIGHT_FORWARDER', '/freight-forwarder/shipments/2');
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
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
    expect(screen.getByRole('cell', { name: formatWhen(newer.plannedMotherArrivalAt) })).toBeInTheDocument();
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

async function openRemoval(reference: string) {
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: `Remove ${reference}` }));
  const dialog = await screen.findByRole('dialog', { name: 'Remove shipment' });
  expect(within(dialog).getByText(`Removing ${reference} archives it and keeps its record. It leaves your company's active shipments.`)).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
  return { user, dialog };
}

describe('shipment removal', () => {
  it.each(['ADMIN', 'LOGISTICS_MANAGER'] as const)('shows removal only for shipments owned by the %s company', async role => {
    const linked = { ...older, id: 8, shipmentReference: 'HBL-LINKED', companyId: 99 };
    vi.mocked(listShipments).mockResolvedValue([newer, linked]);
    openWorkspace(role);
    expect(await screen.findByRole('heading', { name: 'Company shipments' })).toBeInTheDocument();
    expect(screen.getByText('You are signed in to the shipments registered for your company.')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Remove HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'HBL-NEWER' })).toHaveAttribute('href', role === 'ADMIN' ? '/admin/shipments/2' : '/logistics-manager/shipments/2');
    expect(screen.getByRole('link', { name: 'HBL-LINKED' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove HBL-LINKED' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a shipment' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Import shipments' })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it.each(['ADMIN', 'LOGISTICS_MANAGER'] as const)('hides removal on a directly opened importer-linked shipment for a %s', async role => {
    vi.mocked(getShipment).mockResolvedValue({ ...newer, companyId: 99 });
    openWorkspace(role, role === 'ADMIN' ? '/admin/shipments/2' : '/logistics-manager/shipments/2');
    expect(await screen.findByRole('heading', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove HBL-NEWER' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit shipment' })).not.toBeInTheDocument();
  });

  it.each([
    [undefined],
    [null],
    [0],
    [1.5],
  ] as const)('hides removal when companyId is %s', async companyId => {
    vi.mocked(listShipments).mockResolvedValue([{ ...newer, companyId }]);
    openWorkspace('ADMIN');
    expect(await screen.findByRole('link', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove HBL-NEWER' })).not.toBeInTheDocument();
  });

  it.each(['FREIGHT_FORWARDER', 'IMPORTER'] as const)('hides removal from a %s', async role => {
    openWorkspace(role);
    expect(await screen.findByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument();
  });

  it('leaves the shipment unchanged when removal is cancelled', async () => {
    openWorkspace('ADMIN');
    const opener = await screen.findByRole('button', { name: 'Remove HBL-NEWER' });
    const { user, dialog } = await openRemoval('HBL-NEWER');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(archiveShipment).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(screen.getByRole('link', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledTimes(1);
  });

  it('closes the confirmation with Escape before a request starts', async () => {
    openWorkspace('LOGISTICS_MANAGER');
    const { user } = await openRemoval('HBL-OLDER');
    await user.keyboard('{Escape}');
    expect(archiveShipment).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove HBL-OLDER' })).toHaveFocus();
  });

  it('closes the confirmation from the backdrop before a request starts', async () => {
    openWorkspace('ADMIN');
    const { dialog } = await openRemoval('HBL-NEWER');
    fireEvent.click(dialog);
    expect(archiveShipment).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove HBL-NEWER' })).toHaveFocus();
  });

  it('refreshes the active list and shows the server message after removal', async () => {
    let resolveList: (rows: Shipment[]) => void = () => {};
    vi.mocked(listShipments)
      .mockResolvedValueOnce([newer, older])
      .mockImplementationOnce(() => new Promise(resolve => { resolveList = resolve; }));
    vi.mocked(archiveShipment).mockResolvedValue('Shipment removed successfully');
    openWorkspace('ADMIN');
    const { user, dialog } = await openRemoval('HBL-NEWER');
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
    expect(archiveShipment).toHaveBeenCalledWith('session-token', 2);
    expect(await screen.findByText('Shipment removed successfully')).toHaveAttribute('role', 'status');
    expect(screen.getByText('Loading shipments...')).toBeInTheDocument();
    expect(screen.getByText('Shipment removed successfully')).toHaveAttribute('role', 'status');
    resolveList([older]);
    expect(await screen.findByRole('link', { name: 'HBL-OLDER' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'HBL-NEWER' })).not.toBeInTheDocument();
    expect(screen.getByText('Shipment removed successfully')).toHaveAttribute('role', 'status');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a different server message without treating one sentence as the only success', async () => {
    vi.mocked(listShipments).mockResolvedValueOnce([newer]).mockResolvedValueOnce([]);
    vi.mocked(archiveShipment).mockResolvedValue('Archived and retained.');
    openWorkspace('LOGISTICS_MANAGER');
    const { user, dialog } = await openRemoval('HBL-NEWER');
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByText('Archived and retained.')).toHaveAttribute('role', 'status');
    expect(screen.queryByText('Shipment removed successfully')).not.toBeInTheDocument();
  });

  it('sends one request and keeps the dialog open until it finishes', async () => {
    let resolveArchive: (message: string) => void = () => {};
    vi.mocked(archiveShipment).mockImplementation(() => new Promise(resolve => { resolveArchive = resolve; }));
    vi.mocked(listShipments).mockResolvedValue([newer]);
    openWorkspace('ADMIN');
    const { dialog } = await openRemoval('HBL-NEWER');
    const confirm = within(dialog).getByRole('button', { name: 'Remove' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(confirm).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeDisabled();
    fireEvent.keyDown(dialog, { key: 'Escape' });
    fireEvent.click(dialog);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(archiveShipment).toHaveBeenCalledTimes(1);
    expect(dialog).toHaveAttribute('open');
    resolveArchive('Shipment removed successfully');
    expect(await screen.findByText('Shipment removed successfully')).toHaveAttribute('role', 'status');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it.each([
    [400, 'Invalid Shipment ID.'],
    [403, 'You do not have permission to archive this shipment.'],
    [404, 'Shipment not found'],
    [0, 'We could not reach DRIFT. Check your connection and try again.'],
  ] as const)('keeps the shipment and shows a %s failure', async (status, message) => {
    vi.mocked(archiveShipment).mockRejectedValue(new ApiError(message, status));
    openWorkspace('ADMIN');
    const { user, dialog } = await openRemoval('HBL-NEWER');
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.queryByText('Shipment removed successfully')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('refreshes a stale row after a conflict and still shows the failure', async () => {
    vi.mocked(listShipments).mockResolvedValueOnce([newer, older]).mockResolvedValueOnce([older]);
    vi.mocked(archiveShipment).mockRejectedValue(new ApiError('Shipment is already removed or does not exist.', 409));
    openWorkspace('LOGISTICS_MANAGER');
    const { user, dialog } = await openRemoval('HBL-NEWER');
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment is already removed or does not exist.');
    expect(screen.queryByText('Shipment removed successfully')).not.toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'HBL-OLDER' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'HBL-NEWER' })).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Shipment is already removed or does not exist.');
    expect(listShipments).toHaveBeenCalledTimes(2);
  });

  it('ends the session when removal is unauthorized by an ended session', async () => {
    vi.mocked(archiveShipment).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    openWorkspace('ADMIN');
    const { user, dialog } = await openRemoval('HBL-NEWER');
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
    expect(screen.queryByText('Shipment removed successfully')).not.toBeInTheDocument();
  });
});
