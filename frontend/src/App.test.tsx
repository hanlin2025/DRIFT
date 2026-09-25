import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from './App';
import { ApiError, registerAccount, resolveInvitation } from './signup/api';

vi.mock('./signup/api', async importOriginal => ({ ...await importOriginal<typeof import('./signup/api')>(), registerAccount: vi.fn(), resolveInvitation: vi.fn() }));
const invitation = { email: 'alice@example.com', companyName: 'Harbourline Logistics (Demo)', role: 'FREIGHT_FORWARDER' as const, expiresAt: '2030-01-01T00:00:00Z' };
function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

beforeEach(() => {
  vi.resetAllMocks();
  window.history.replaceState(null, '', '/signup#invite=' + 'a'.repeat(43));
  vi.mocked(resolveInvitation).mockResolvedValue(invitation);
  vi.mocked(registerAccount).mockResolvedValue({ id: 1, fullName: 'Alice', email: invitation.email, role: invitation.role });
});

async function submit() {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Full name'), 'Alice');
  await user.type(screen.getByLabelText('Password', { exact: true }), 'Example123');
  await user.type(screen.getByLabelText('Confirm password'), 'Example123');
  await user.click(screen.getByRole('button', { name: /Create account/ }));
}

describe('registration routing', () => {
  it('routes to login only after backend success and does not carry the password', async () => {
    render(<MemoryRouter initialEntries={['/signup']}><AppRoutes /><Location /></MemoryRouter>);
    await submit();
    expect(await screen.findByText('Account created successfully.')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(screen.getByLabelText('Work email')).toHaveValue(invitation.email);
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByRole('button', { name: /Log in/ })).toBeEnabled();
  });
  it('stays on signup when registration fails', async () => {
    vi.mocked(registerAccount).mockRejectedValue(new ApiError('Email already registered', 409));
    render(<MemoryRouter initialEntries={['/signup']}><AppRoutes /><Location /></MemoryRouter>);
    await submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Email already registered');
    expect(screen.getByTestId('location')).toHaveTextContent('/signup');
  });
  it('supports opening login directly without a success notice', async () => {
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /></MemoryRouter>);
    expect(screen.queryByText('Account created successfully.')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Work email')).toHaveValue('');
    await waitFor(() => expect(document.title).toBe('Log in | DRIFT'));
  });
});
