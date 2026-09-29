import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));

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
});

describe('shipment registration form', () => {
  it('offers registration on the freight-forwarder portfolio and not on the importer overview', async () => {
    writeSession(importer);
    vi.mocked(loadSession).mockImplementation(async token => ({ ...importer, token }));
    render(<MemoryRouter initialEntries={['/importer']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a shipment' })).not.toBeInTheDocument();
    cleanup();

    writeSession(forwarder);
    vi.mocked(loadSession).mockImplementation(async token => ({ ...forwarder, token }));
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
    expect(screen.getByText('This shipment is registered for Harbourline Logistics (Demo).')).toBeInTheDocument();
  });

  it('checks the itinerary on the form', async () => {
    writeSession(forwarder);
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Register shipment/ }));
    expect(screen.getByText('Shipment reference is required')).toBeInTheDocument();
    expect(screen.getByText('Planned mother-vessel arrival is required')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Shipment reference'), 'HL-1001');
    await user.type(screen.getByLabelText('Origin'), 'Singapore');
    await user.type(screen.getByLabelText('Destination'), 'Jakarta');
    await user.type(screen.getByLabelText('Mother vessel'), 'Ever Steady');
    await user.type(screen.getByLabelText('Feeder vessel'), 'Straits Feeder');
    fireEvent.change(screen.getByLabelText('Planned mother-vessel arrival'), { target: { value: '2026-10-02T08:00' } });
    fireEvent.change(screen.getByLabelText('Planned feeder-vessel departure'), { target: { value: '2026-10-01T08:00' } });
    await user.click(screen.getByRole('button', { name: /Register shipment/ }));
    expect(screen.getByText('Planned feeder-vessel departure must be after planned mother-vessel arrival')).toBeInTheDocument();
  });
});
