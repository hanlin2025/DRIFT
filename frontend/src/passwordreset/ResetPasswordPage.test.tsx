import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { ApiError } from '../signup/api';
import { clearSession } from '../session/session';
import { RESET_LINK_MESSAGE } from './validation';
import { submitPasswordReset } from './api';

vi.mock('./api', () => ({ requestPasswordReset: vi.fn(), submitPasswordReset: vi.fn() }));

const token = 'a'.repeat(43);
function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  window.history.replaceState(null, '', `/reset-password#token=${token}`);
  vi.mocked(submitPasswordReset).mockResolvedValue('Your password has been reset');
});

describe('reset password page', () => {
  it('rejects a missing link before showing the form', () => {
    window.history.replaceState(null, '', '/reset-password');
    render(<MemoryRouter initialEntries={['/reset-password']}><AppRoutes /></MemoryRouter>);
    expect(screen.getByRole('alert')).toHaveTextContent(RESET_LINK_MESSAGE);
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Request a new link' })).toHaveAttribute('href', '/forgot-password');
  });

  it('blocks a short password and a confirmation that does not match', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/reset-password']}><AppRoutes /></MemoryRouter>);
    await user.type(screen.getByLabelText('New password'), 'short1A');
    await user.type(screen.getByLabelText('Confirm password'), 'short1A');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));
    expect(screen.getByText('Password must be at least 8 characters')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('New password'));
    await user.type(screen.getByLabelText('New password'), 'Example123');
    await user.clear(screen.getByLabelText('Confirm password'));
    await user.type(screen.getByLabelText('Confirm password'), 'Example124');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));
    expect(screen.getByText('Passwords do not match')).toBeInTheDocument();
    expect(submitPasswordReset).not.toHaveBeenCalled();
  });

  it('saves a valid password and returns to login', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/reset-password']}><AppRoutes /><Location /></MemoryRouter>);
    await user.type(screen.getByLabelText('New password'), 'Example123');
    await user.type(screen.getByLabelText('Confirm password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));
    expect(submitPasswordReset).toHaveBeenCalledExactlyOnceWith(token, 'Example123');
    expect(await screen.findByText('Your password has been reset')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('shows an invalid link returned by the API', async () => {
    vi.mocked(submitPasswordReset).mockRejectedValue(new ApiError(RESET_LINK_MESSAGE, 400, { token: RESET_LINK_MESSAGE }));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/reset-password']}><AppRoutes /></MemoryRouter>);
    await user.type(screen.getByLabelText('New password'), 'Example123');
    await user.type(screen.getByLabelText('Confirm password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(RESET_LINK_MESSAGE);
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
  });

  it('shows a password error from the API on the field', async () => {
    vi.mocked(submitPasswordReset).mockRejectedValue(new ApiError('Password must not contain spaces', 400, { password: 'Password must not contain spaces' }));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/reset-password']}><AppRoutes /></MemoryRouter>);
    await user.type(screen.getByLabelText('New password'), 'Example123');
    await user.type(screen.getByLabelText('Confirm password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));
    expect(await screen.findByText('Password must not contain spaces')).toBeInTheDocument();
    expect(screen.getByLabelText('New password')).toBeInTheDocument();
  });
});
