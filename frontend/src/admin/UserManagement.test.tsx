import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession, login } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { getCdg201ArrivalEvidence, getShipment, getShipmentHistory, getShipmentTracking, getVesselTrack, listShipments } from '../shipment/api';
import { WorkspacePage } from '../workspace/WorkspaceRoute';
import { assignUser, listAccountStatusAudits, listAssignableOrganisations, listAssignableRoles, listAssignableUsers, listAssignmentAudits, removeUser } from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ listShipments: vi.fn(), createShipment: vi.fn(), updateShipment: vi.fn(), getShipment: vi.fn(), getShipmentTracking: vi.fn(), getCdg201ArrivalEvidence: vi.fn(), archiveShipment: vi.fn(), getVesselTrack: vi.fn(), getShipmentHistory: vi.fn() }));
vi.mock('./api', () => ({
  listAssignableUsers: vi.fn(),
  listAssignableRoles: vi.fn(),
  listAssignableOrganisations: vi.fn(),
  listAssignmentAudits: vi.fn(),
  assignUser: vi.fn(),
  removeUser: vi.fn(),
  listAccountStatusAudits: vi.fn(),
}));

const admin: Session = {
  token: 'admin-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 9,
  fullName: 'Amina Rahman',
  email: 'amina@example.com',
  role: 'ADMIN',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
};
const forwarder: Session = { ...admin, token: 'forwarder-token', id: 4, fullName: 'Alice Tan', email: 'alice@example.com', role: 'FREIGHT_FORWARDER' };
const users = [
  { id: 4, fullName: 'Alice Tan', email: 'alice@example.com', role: 'FREIGHT_FORWARDER' as const, organisation: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' }, status: 'ACTIVE' as const },
  { id: 9, fullName: 'Amina Rahman', email: 'amina@example.com', role: 'ADMIN' as const, organisation: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' }, status: 'ACTIVE' as const },
  { id: 5, fullName: 'Ben Ong', email: 'ben@example.com', role: 'IMPORTER' as const, organisation: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' }, status: 'REMOVED' as const },
];
const removal = {
  id: 1,
  recordedAt: '2026-10-10T00:22:00Z',
  operator: { id: 9, fullName: 'Amina Rahman', email: 'amina@example.com' },
  target: { id: 4, fullName: 'Alice Tan', email: 'alice@example.com' },
  organisationId: 1,
  previousStatus: 'ACTIVE' as const,
  newStatus: 'REMOVED' as const,
};
const roles = [
  { code: 'ADMIN' as const, label: 'Administrator' },
  { code: 'FREIGHT_FORWARDER' as const, label: 'Freight forwarder' },
  { code: 'IMPORTER' as const, label: 'Importer' },
  { code: 'LOGISTICS_MANAGER' as const, label: 'Logistics manager' },
];
const organisations = [
  { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
  { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' },
];

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(getShipmentHistory).mockResolvedValue({ shipmentId: 1, earlierChangesUnrecorded: false, entries: [] });
  vi.mocked(loadSession).mockImplementation(async token => ({ ...(token === admin.token ? admin : forwarder), token }));
  vi.mocked(login).mockResolvedValue(admin);
  vi.mocked(listShipments).mockResolvedValue([]);
  vi.mocked(listAssignableUsers).mockResolvedValue(users);
  vi.mocked(listAssignableRoles).mockResolvedValue(roles);
  vi.mocked(listAssignableOrganisations).mockResolvedValue(organisations);
  vi.mocked(listAssignmentAudits).mockResolvedValue([]);
  vi.mocked(assignUser).mockResolvedValue({ ...users[0], role: 'IMPORTER', organisation: organisations[1] });
  vi.mocked(removeUser).mockResolvedValue({ ...users[0], status: 'REMOVED' });
  vi.mocked(listAccountStatusAudits).mockResolvedValue([]);
});

describe('user management', () => {
  it('assigns the selected user and confirms the saved assignment', async () => {
    writeSession(admin);
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'User management' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'User management' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Company shipments' })).toHaveAttribute('href', '/admin/shipments');
    expect(screen.getByRole('link', { name: 'Profile' })).toHaveAttribute('href', '/profile');
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    expect(screen.getByLabelText('Organisation')).toHaveValue('1');
    expect(screen.getByLabelText('Role')).toHaveValue('FREIGHT_FORWARDER');
    await user.selectOptions(screen.getByLabelText('Organisation'), 'Straits Fresh Imports (Demo)');
    await user.selectOptions(screen.getByLabelText('Role'), 'Importer');
    await user.click(screen.getByRole('button', { name: /Save assignment/ }));

    expect(assignUser).toHaveBeenCalledWith('admin-token', 4, 2, 'IMPORTER');
    expect(await screen.findByText('User assigned successfully')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('shows the assignment history after a change is saved', async () => {
    writeSession(admin);
    const entry = {
      id: 1,
      recordedAt: '2026-10-10T00:22:00Z',
      operator: { id: 9, fullName: 'Amina Rahman', email: 'amina@example.com' },
      target: { id: 4, fullName: 'Alice Tan', email: 'alice@example.com' },
      previousRole: 'FREIGHT_FORWARDER' as const,
      assignedRole: 'IMPORTER' as const,
      previousOrganisation: organisations[0],
      organisation: organisations[1],
    };
    let saved = false;
    vi.mocked(listAssignmentAudits).mockImplementation(async () => saved ? [entry] : []);
    vi.mocked(assignUser).mockImplementation(async () => {
      saved = true;
      return { ...users[0], role: 'IMPORTER', organisation: organisations[1] };
    });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByText('No assignment changes yet.')).toBeInTheDocument();
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    await user.selectOptions(screen.getByLabelText('Organisation'), 'Straits Fresh Imports (Demo)');
    await user.selectOptions(screen.getByLabelText('Role'), 'Importer');
    await user.click(screen.getByRole('button', { name: /Save assignment/ }));
    expect(await screen.findByText('Freight forwarder at Harbourline Logistics (Demo) → Importer at Straits Fresh Imports (Demo)')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Assignment history' })).toBeInTheDocument();
    expect(screen.queryByText('No assignment changes yet.')).not.toBeInTheDocument();
  });

  it('keeps the administrator signed in when their own assignment is unchanged', async () => {
    writeSession(admin);
    vi.mocked(assignUser).mockResolvedValueOnce(users[1]);
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);
    await user.click(await screen.findByRole('radio', { name: /Amina Rahman/ }));
    await user.click(screen.getByRole('button', { name: /Save assignment/ }));
    expect(assignUser).toHaveBeenCalledWith('admin-token', 9, 1, 'ADMIN');
    expect(await screen.findByText('User assigned successfully')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('ends the administrator session when their own organisation changes', async () => {
    writeSession(admin);
    vi.mocked(assignUser).mockResolvedValueOnce({ ...users[1], organisation: organisations[1] });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);
    await user.click(await screen.findByRole('radio', { name: /Amina Rahman/ }));
    await user.selectOptions(screen.getByLabelText('Organisation'), 'Straits Fresh Imports (Demo)');
    await user.click(screen.getByRole('button', { name: /Save assignment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(screen.queryByRole('heading', { name: 'User management' })).not.toBeInTheDocument();
  });

  it('asks for an active organisation when the current one is inactive', async () => {
    writeSession(admin);
    vi.mocked(listAssignableUsers).mockResolvedValue([
      { ...users[0], organisation: { id: 99, code: 'CLOSED_CO', name: 'Closed Company' } },
    ]);
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /></MemoryRouter>);
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    expect(screen.getByLabelText('Organisation')).toHaveValue('');
    expect(screen.getByText('The current organisation is inactive. Choose an active organisation.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Save assignment/ })).toBeDisabled();
  });

  it('sends an administrator from a freight-forwarder shipment to that shipment in company shipments', async () => {
    writeSession(admin);
    vi.mocked(getShipment).mockResolvedValue({
      id: 15,
      shipmentReference: 'HBL-15',
      origin: 'Singapore',
      destination: 'Jakarta',
      transshipmentPort: 'Singapore',
      motherVessel: 'Ever Steady',
      plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
      feederVessel: 'Straits Feeder',
      plannedFeederDepartureAt: '2026-10-03T00:00:00Z',
      createdAt: '2026-09-29T04:00:00Z',
      version: 0,
      companyId: 1,
      connectionWindow: null,
      remainingConnectionWindow: null,
      risk: null,
      status: null,
      motherVesselPosition: null,
      feederVesselPosition: null,
      arrivalComparison: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({ motherVesselName: 'Ever Steady', motherVessel: null, motherVesselNameWarning: null, feederVesselName: 'Straits Feeder', feederVessel: null, feederVesselNameWarning: null });
    vi.mocked(getCdg201ArrivalEvidence).mockResolvedValue(null);
    vi.mocked(getVesselTrack).mockResolvedValue({
      vessel: 'MOTHER',
      vesselName: null,
      mmsi: null,
      windowHours: 24,
      from: '2026-10-04T03:00:00Z',
      to: '2026-10-05T03:00:00Z',
      slowdownThresholdKnots: 3,
      pointLimit: 500,
      truncated: false,
      points: [],
    });
    render(<MemoryRouter initialEntries={['/freight-forwarder/shipments/15']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'HBL-15' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/shipments/15');
    expect(screen.queryByRole('heading', { name: 'User management' })).not.toBeInTheDocument();
  });

  it('sends a logistics manager from user management to the company shipment workspace', async () => {
    const manager: Session = { ...forwarder, token: 'manager-token', role: 'LOGISTICS_MANAGER' };
    vi.mocked(loadSession).mockImplementation(async token => ({ ...manager, token }));
    writeSession(manager);
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Company shipments' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a shipment' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Import shipments' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'User management' })).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/logistics-manager');
  });

  it('shows the validation error and keeps the administrator signed in', async () => {
    writeSession(admin);
    vi.mocked(assignUser).mockRejectedValueOnce(new ApiError('Invalid role or organisation selected.', 400));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /></MemoryRouter>);
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    await user.click(screen.getByRole('button', { name: /Save assignment/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid role or organisation selected.');
    expect(screen.getByRole('heading', { name: 'User management' })).toBeInTheDocument();
  });

  it('sends a freight forwarder away from user management', async () => {
    writeSession(forwarder);
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'User management' })).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
  });

  it('shows the user management menu only to an administrator', () => {
    const { rerender } = render(<MemoryRouter><WorkspacePage account={forwarder} onSignOut={() => undefined} onSessionEnded={() => undefined} /></MemoryRouter>);
    expect(screen.queryByRole('link', { name: 'User management' })).not.toBeInTheDocument();
    rerender(<MemoryRouter><WorkspacePage account={admin} onSignOut={() => undefined} onSessionEnded={() => undefined} /></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'User management' })).toHaveAttribute('href', '/admin');
  });

  it('opens user management after an administrator logs in', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /><Location /></MemoryRouter>);
    await user.type(screen.getByLabelText('Work email'), 'amina@example.com');
    await user.type(screen.getByLabelText('Password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Log in/ }));
    expect(await screen.findByRole('heading', { name: 'User management' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('offers a retry when the user list cannot be loaded', async () => {
    writeSession(admin);
    vi.mocked(listAssignableUsers).mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT. Check your connection and try again.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('radio', { name: /Alice Tan/ })).toBeInTheDocument();
  });
});

describe('account removal', () => {
  function renderPage() {
    writeSession(admin);
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);
  }

  it('shows each user status and filters the list by status', async () => {
    renderPage();
    const user = userEvent.setup();
    expect(await screen.findByRole('radio', { name: /Alice Tan.*Active/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Ben Ong.*Removed/ })).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Show'), 'Removed');
    expect(screen.getByRole('radio', { name: /Ben Ong/ })).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /Alice Tan/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /Amina Rahman/ })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Show'), 'Active');
    expect(screen.queryByRole('radio', { name: /Ben Ong/ })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Alice Tan/ })).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Show'), 'All users');
    expect(screen.getAllByRole('radio')).toHaveLength(3);
  });

  it('shows a notice when no user matches the status', async () => {
    vi.mocked(listAssignableUsers).mockResolvedValue([users[0], users[1]]);
    renderPage();
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('Show'), 'Removed');
    expect(screen.getByText('No users match this status.')).toBeInTheDocument();
  });

  it('hides Remove for the administrator and for removed users, and locks a removed user', async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /Amina Rahman/ }));
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /Ben Ong/ }));
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Role')).toBeDisabled();
    expect(screen.getByRole('button', { name: /Save assignment/ })).toBeDisabled();

    await user.click(screen.getByRole('radio', { name: /Alice Tan/ }));
    expect(screen.getByRole('button', { name: 'Remove Alice Tan' })).toBeInTheDocument();
    expect(screen.getByLabelText('Role')).toBeEnabled();
  });

  it('removes the confirmed user, updates the row and refreshes the history', async () => {
    let removed = false;
    vi.mocked(removeUser).mockImplementation(async () => {
      removed = true;
      return { ...users[0], status: 'REMOVED' };
    });
    vi.mocked(listAccountStatusAudits).mockImplementation(async () => removed ? [removal] : []);
    renderPage();
    const user = userEvent.setup();
    expect(await screen.findByText('No account removals yet.')).toBeInTheDocument();
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    await user.click(screen.getByRole('button', { name: 'Remove Alice Tan' }));

    const dialog = screen.getByRole('dialog', { name: 'Remove user' });
    expect(dialog).toHaveTextContent('Alice Tan (alice@example.com)');
    expect(dialog).toHaveTextContent('Their current session will end.');
    expect(assignUser).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));

    expect(removeUser).toHaveBeenCalledWith('admin-token', 4);
    expect(await screen.findByText("Alice Tan's account was removed.")).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Alice Tan.*Removed/ })).toBeChecked();
    expect(screen.queryByRole('button', { name: 'Remove Alice Tan' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Role')).toBeDisabled();
    expect(screen.getByRole('button', { name: /Save assignment/ })).toBeDisabled();
    const history = screen.getByRole('region', { name: 'Removal history' });
    expect(await within(history).findByText('Active → Removed')).toBeInTheDocument();
    expect(history).toHaveTextContent('Amina Rahman removed Alice Tan');
    expect(screen.queryByText('No account removals yet.')).not.toBeInTheDocument();
    expect(assignUser).not.toHaveBeenCalled();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('does nothing when the removal is cancelled', async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    await user.click(screen.getByRole('button', { name: 'Remove Alice Tan' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Remove user' })).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(removeUser).not.toHaveBeenCalled();
    expect(assignUser).not.toHaveBeenCalled();
    expect(screen.getByRole('radio', { name: /Alice Tan.*Active/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove Alice Tan' })).toHaveFocus();
  });

  it('shows the API error and keeps the user active', async () => {
    vi.mocked(removeUser).mockRejectedValueOnce(new ApiError('This account has already been removed.', 409));
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    await user.click(screen.getByRole('button', { name: 'Remove Alice Tan' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Remove user' })).getByRole('button', { name: 'Remove' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This account has already been removed.');
    expect(screen.getByRole('radio', { name: /Alice Tan.*Active/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'User management' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('ends the session when removal returns 401', async () => {
    vi.mocked(removeUser).mockRejectedValueOnce(new ApiError('Your session has ended. Log in again.', 401));
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /Alice Tan/ }));
    await user.click(screen.getByRole('button', { name: 'Remove Alice Tan' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Remove user' })).getByRole('button', { name: 'Remove' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(screen.queryByRole('heading', { name: 'User management' })).not.toBeInTheDocument();
  });

  it('shows an empty removal history', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Removal history' })).toBeInTheDocument();
    expect(screen.getByText('No account removals yet.')).toBeInTheDocument();
  });
});
