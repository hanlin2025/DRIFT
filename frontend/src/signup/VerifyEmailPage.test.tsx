import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { ApiError, resendVerification, verifyEmail } from './api';
import { INCOMPLETE_VERIFICATION_LINK, INVALID_VERIFICATION_LINK } from './VerifyEmailPage';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(), verifyEmail: vi.fn(), resendVerification: vi.fn(),
}));

const TOKEN = 'T'.repeat(43);

function renderAt(hash: string) {
  window.history.replaceState(null, '', `/verify-email${hash}`);
  return render(<MemoryRouter initialEntries={['/verify-email']}><AppRoutes /></MemoryRouter>);
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(verifyEmail).mockResolvedValue({ message: 'Your email has been verified. You can now sign in.' });
  vi.mocked(resendVerification).mockResolvedValue({ message: 'If this email is awaiting verification, a new link has been sent.' });
});

describe('verify email page', () => {
  it('verifies the token from the link, clears it from the address bar, and links to sign in', async () => {
    renderAt(`#token=${TOKEN}`);

    expect(window.location.hash).toBe('');
    expect(window.location.pathname).toBe('/verify-email');
    expect(document.title).toBe('Verify email | DRIFT');
    expect(await screen.findByText('Your email has been verified. You can now sign in.')).toHaveAttribute('role', 'status');
    expect(verifyEmail).toHaveBeenCalledExactlyOnceWith(TOKEN);
    expect(screen.getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
  });

  it('shows the invalid-link message and a resend form when the server rejects the link', async () => {
    vi.mocked(verifyEmail).mockRejectedValueOnce(new ApiError(INVALID_VERIFICATION_LINK, 400, { token: INVALID_VERIFICATION_LINK }));
    const user = userEvent.setup();
    renderAt(`#token=${TOKEN}`);

    expect(await screen.findByRole('alert')).toHaveTextContent(INVALID_VERIFICATION_LINK);
    await user.type(screen.getByLabelText('Signup email'), 'alex@pacificgate.example');
    await user.click(screen.getByRole('button', { name: 'Send a new verification link' }));
    expect(resendVerification).toHaveBeenCalledWith('alex@pacificgate.example');
    expect(await screen.findByRole('status')).toHaveTextContent('If this email is awaiting verification, a new link has been sent.');
  });

  it('treats a malformed token as invalid without calling the server', async () => {
    renderAt('#token=short');
    expect(await screen.findByRole('alert')).toHaveTextContent(INVALID_VERIFICATION_LINK);
    expect(verifyEmail).not.toHaveBeenCalled();
  });

  it('explains an incomplete link when there is no token', async () => {
    renderAt('');
    expect(await screen.findByRole('alert')).toHaveTextContent(INCOMPLETE_VERIFICATION_LINK);
    expect(screen.getByLabelText('Signup email')).toBeInTheDocument();
    expect(verifyEmail).not.toHaveBeenCalled();
  });

  it('offers a retry when DRIFT cannot be reached', async () => {
    vi.mocked(verifyEmail).mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    const user = userEvent.setup();
    renderAt(`#token=${TOKEN}`);

    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT to verify your email.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Your email has been verified. You can now sign in.')).toHaveAttribute('role', 'status');
    expect(verifyEmail).toHaveBeenCalledTimes(2);
  });
});
