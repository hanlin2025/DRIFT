import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { ApiError } from '../signup/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { loadSession, login } from './api';

vi.mock('./api', () => ({ login: vi.fn(), loadSession: vi.fn() }));

const session: Session = {
  token: 'session-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 4,
  fullName: 'Alice Tan',
  email: 'alice@example.com',
  role: 'FREIGHT_FORWARDER',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(login).mockResolvedValue(session);
  vi.mocked(loadSession).mockImplementation(async token => ({ ...session, token }));
});

describe('login and role routing', () => {
  it('asks for both fields before calling the API', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: /Log in/ }));
    expect(screen.getByText('Email is required')).toBeInTheDocument();
    expect(screen.getByText('Password is required')).toBeInTheDocument();
    expect(login).not.toHaveBeenCalled();
  });

  it('opens the freight-forwarder portfolio after a valid login', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /><Location /></MemoryRouter>);
    await user.type(screen.getByLabelText('Work email'), 'alice@example.com');
    await user.type(screen.getByLabelText('Password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Log in/ }));
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(screen.getByText('Harbourline Logistics (Demo)')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
    expect(sessionStorage.getItem('drift.session')).not.toContain('Example123');
  });

  it('opens the importer overview for an importer', async () => {
    vi.mocked(login).mockResolvedValue({ ...session, role: 'IMPORTER' });
    vi.mocked(loadSession).mockImplementation(async token => ({ ...session, role: 'IMPORTER', token }));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /><Location /></MemoryRouter>);
    await user.type(screen.getByLabelText('Work email'), 'alice@example.com');
    await user.type(screen.getByLabelText('Password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Log in/ }));
    expect(await screen.findByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/importer');
  });

  it('stays on login when the credentials are rejected', async () => {
    vi.mocked(login).mockRejectedValue(new ApiError('The email or password is incorrect.', 401));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /><Location /></MemoryRouter>);
    await user.type(screen.getByLabelText('Work email'), 'alice@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: /Log in/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The email or password is incorrect.');
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
  });

  it('sends an expired session back to login', async () => {
    writeSession({ ...session, expiresAt: '2020-01-01T00:00:00Z' });
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(loadSession).not.toHaveBeenCalled();
  });

  it('sends a freight forwarder away from the importer page', async () => {
    writeSession(session);
    render(<MemoryRouter initialEntries={['/importer']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
  });

  it('signs out back to login', async () => {
    writeSession(session);
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /><Location /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });
});
