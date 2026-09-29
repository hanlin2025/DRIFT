import { StrictMode } from 'react';
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
    vi.mocked(login).mockRejectedValue(new ApiError('Invalid email or password.', 401));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /><Location /></MemoryRouter>);
    await user.type(screen.getByLabelText('Work email'), 'alice@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: /Log in/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
  });

  it('sends an expired session back to login', async () => {
    writeSession({ ...session, expiresAt: '2020-01-01T00:00:00Z' });
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
    expect(loadSession).not.toHaveBeenCalled();
  });

  it('still shows the ended-session message when the check runs twice', async () => {
    writeSession({ ...session, expiresAt: '2020-01-01T00:00:00Z' });
    render(<StrictMode><MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter></StrictMode>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
    expect(loadSession).not.toHaveBeenCalled();
  });

  it('clears an expired session when opening the home page', async () => {
    writeSession({ ...session, expiresAt: '2020-01-01T00:00:00Z' });
    render(<MemoryRouter initialEntries={['/']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(sessionStorage.getItem('drift.session')).toBeNull();
    expect(loadSession).not.toHaveBeenCalled();
  });

  it('clears an expired session from an unknown path', async () => {
    writeSession({ ...session, expiresAt: '2020-01-01T00:00:00Z' });
    render(<MemoryRouter initialEntries={['/not-a-page']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });

  it('keeps a valid session when the server cannot be reached', async () => {
    writeSession(session);
    vi.mocked(loadSession).mockRejectedValueOnce(new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 503));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toHaveTextContent('DRIFT returned an unexpected response. Please try again shortly.');
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
    expect(sessionStorage.getItem('drift.session')).toContain('session-token');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
  });

  it('clears the session when the server rejects it', async () => {
    writeSession(session);
    vi.mocked(loadSession).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
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

describe('login acceptance criteria', () => {
  async function submit(email: string, password: string) {
    const user = userEvent.setup();
    if (email) await user.type(screen.getByLabelText('Work email'), email);
    if (password) await user.type(screen.getByLabelText('Password'), password);
    await user.click(screen.getByRole('button', { name: /Log in/ }));
  }

  it('does not create a session when the credentials are rejected', async () => {
    vi.mocked(login).mockRejectedValue(new ApiError('Invalid email or password.', 401));
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /><Location /></MemoryRouter>);
    await submit('nobody@example.com', 'Example123');
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(screen.queryByText('Harbourline Logistics (Demo)')).not.toBeInTheDocument();
  });

  it('asks for the password when only the email is entered', async () => {
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /></MemoryRouter>);
    await submit('alice@example.com', '');
    expect(screen.getByText('Password is required')).toBeInTheDocument();
    expect(screen.queryByText('Email is required')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveFocus();
    expect(login).not.toHaveBeenCalled();
  });

  it('asks for the email when it is blank', async () => {
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /></MemoryRouter>);
    await submit('   ', 'Example123');
    expect(screen.getByText('Email is required')).toBeInTheDocument();
    expect(screen.getByLabelText('Work email')).toHaveFocus();
    expect(login).not.toHaveBeenCalled();
  });

  it.each(['/importer', '/freight-forwarder'])('sends a signed-out visitor from %s to login without protected data', async path => {
    render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('Harbourline Logistics (Demo)')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
    expect(loadSession).not.toHaveBeenCalled();
  });

  it('asks the user to log in again when the session expires in an open workspace', async () => {
    const expiresAt = new Date(Date.now() + 300).toISOString();
    writeSession({ ...session, expiresAt });
    vi.mocked(loadSession).mockImplementation(async token => ({ ...session, expiresAt, token }));
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /><Location /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(await screen.findByRole('alert', {}, { timeout: 2000 })).toHaveTextContent('Your session has ended. Log in again.');
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(screen.queryByText('Harbourline Logistics (Demo)')).not.toBeInTheDocument();
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });
});
