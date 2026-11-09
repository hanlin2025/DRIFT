import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { ApiError } from '../signup/api';
import { clearSession } from '../session/session';
import { requestPasswordReset } from './api';

vi.mock('./api', () => ({ requestPasswordReset: vi.fn(), submitPasswordReset: vi.fn() }));

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(requestPasswordReset).mockResolvedValue('If this email is registered, a reset link has been sent');
});

describe('forgot password page', () => {
  it('opens from the login page', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/login']}><AppRoutes /><Location /></MemoryRouter>);
    await user.click(screen.getByRole('link', { name: 'Forgot password?' }));
    expect(await screen.findByRole('heading', { name: 'Forgot your password?' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/forgot-password');
  });

  it('asks for a valid email before calling the API', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/forgot-password']}><AppRoutes /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: /Send reset link/ }));
    expect(screen.getByText('Email is required')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Work email'), 'not-an-email');
    await user.click(screen.getByRole('button', { name: /Send reset link/ }));
    expect(screen.getByText('Email must be a valid email address')).toBeInTheDocument();
    expect(requestPasswordReset).not.toHaveBeenCalled();
  });

  it('shows the generic success message', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/forgot-password']}><AppRoutes /></MemoryRouter>);
    await user.type(screen.getByLabelText('Work email'), 'alex@example.com');
    await user.click(screen.getByRole('button', { name: /Send reset link/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('If this email is registered, a reset link has been sent');
    expect(requestPasswordReset).toHaveBeenCalledExactlyOnceWith('alex@example.com');
    expect(screen.queryByLabelText('Work email')).not.toBeInTheDocument();
  });

  it('shows a failure without claiming the link was sent', async () => {
    vi.mocked(requestPasswordReset).mockRejectedValue(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/forgot-password']}><AppRoutes /></MemoryRouter>);
    await user.type(screen.getByLabelText('Work email'), 'alex@example.com');
    await user.click(screen.getByRole('button', { name: /Send reset link/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
