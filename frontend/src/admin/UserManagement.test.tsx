import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession, login } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { listShipments } from '../shipment/api';
import { WorkspacePage } from '../workspace/WorkspaceRoute';
import { assignUser, listAssignableOrganisations, listAssignableRoles, listAssignableUsers, listAssignmentAudits } from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ listShipments: vi.fn(), createShipment: vi.fn(), updateShipment: vi.fn(), getShipment: vi.fn(), getShipmentTracking: vi.fn() }));
vi.mock('./api', () => ({
  listAssignableUsers: vi.fn(),
  listAssignableRoles: vi.fn(),
  listAssignableOrganisations: vi.fn(),
  listAssignmentAudits: vi.fn(),
  assignUser: vi.fn(),
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
  { id: 4, fullName: 'Alice Tan', email: 'alice@example.com', role: 'FREIGHT_FORWARDER' as const, organisation: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' } },
  { id: 9, fullName: 'Amina Rahman', email: 'amina@example.com', role: 'ADMIN' as const, organisation: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' } },
];
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
  vi.mocked(loadSession).mockImplementation(async token => ({ ...(token === admin.token ? admin : forwarder), token }));
  vi.mocked(login).mockResolvedValue(admin);
  vi.mocked(listShipments).mockResolvedValue([]);
  vi.mocked(listAssignableUsers).mockResolvedValue(users);
  vi.mocked(listAssignableRoles).mockResolvedValue(roles);
  vi.mocked(listAssignableOrganisations).mockResolvedValue(organisations);
  vi.mocked(listAssignmentAudits).mockResolvedValue([]);
  vi.mocked(assignUser).mockResolvedValue({ ...users[0], role: 'IMPORTER', organisation: organisations[1] });
});

describe('user management', () => {
  it('assigns the selected user and confirms the saved assignment', async () => {
    writeSession(admin);
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'User management' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'User management' })).toHaveAttribute('aria-current', 'page');
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

  it('sends an administrator from the freight forwarder page to user management', async () => {
    writeSession(admin);
    render(<MemoryRouter initialEntries={['/freight-forwarder/shipments/15']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'User management' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/admin');
  });

  it('sends a logistics manager to the freight forwarder home', async () => {
    const manager: Session = { ...forwarder, token: 'manager-token', role: 'LOGISTICS_MANAGER' };
    vi.mocked(loadSession).mockImplementation(async token => ({ ...manager, token }));
    writeSession(manager);
    render(<MemoryRouter initialEntries={['/admin']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'User management' })).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
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
