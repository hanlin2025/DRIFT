import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SignupPage } from './SignupPage';
import { ApiError, registerAccount, resolveInvitation } from './api';

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), registerAccount: vi.fn(), resolveInvitation: vi.fn() }));
const invitation = { email: 'alice@example.com', companyName: 'Harbourline Logistics (Demo)', role: 'FREIGHT_FORWARDER' as const, expiresAt: '2030-01-01T00:00:00Z' };

beforeEach(() => {
  vi.resetAllMocks();
  window.history.replaceState(null, '', '/signup#invite=' + 'a'.repeat(43));
  vi.mocked(resolveInvitation).mockResolvedValue(invitation);
  vi.mocked(registerAccount).mockResolvedValue({ id: 1, fullName: 'Alice', email: invitation.email, role: invitation.role });
});

async function completeForm() {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Full name'), 'Alice');
  await user.type(screen.getByLabelText('Password', { exact: true }), 'Example123');
  await user.type(screen.getByLabelText('Confirm password'), 'Example123');
  return user;
}

describe('signup form', () => {
  it('loads assigned company, email and role as read-only values', async () => {
    render(<SignupPage />);
    expect(await screen.findByText(invitation.companyName)).toBeInTheDocument();
    expect(screen.getByLabelText(/Email/)).toHaveAttribute('readonly');
    expect(screen.getByLabelText(/Your role/)).toHaveValue('Freight forwarder');
    expect(resolveInvitation).toHaveBeenCalledWith('a'.repeat(43));
  });
  it('shows validation errors and prevents an invalid request', async () => {
    render(<SignupPage />);
    await userEvent.click(await screen.findByRole('button', { name: /Create account/ }));
    expect(screen.getByText('Enter your full name.')).toBeInTheDocument();
    expect(registerAccount).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Full name')).toHaveFocus();
  });
  it('prevents mismatched passwords', async () => {
    render(<SignupPage />);
    const user = await completeForm();
    await user.type(screen.getByLabelText('Confirm password'), 'different');
    await user.click(screen.getByRole('button', { name: /Create account/ }));
    expect(screen.getByText('Your passwords do not match.')).toBeInTheDocument();
    expect(registerAccount).not.toHaveBeenCalled();
  });
  it('submits only the invited identity and never the company or role', async () => {
    render(<SignupPage />);
    const user = await completeForm();
    await user.click(screen.getByRole('button', { name: /Create account/ }));
    expect(registerAccount).toHaveBeenCalledExactlyOnceWith({ fullName: 'Alice', email: invitation.email, password: 'Example123', invitationToken: 'a'.repeat(43) });
    expect(await screen.findByRole('status')).toHaveTextContent('Your account has been created');
  });
  it('shows duplicate-account errors without pretending registration succeeded', async () => {
    vi.mocked(registerAccount).mockRejectedValue(new ApiError('An account with this email already exists', 409));
    render(<SignupPage />);
    const user = await completeForm();
    await user.click(screen.getByRole('button', { name: /Create account/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already exists');
    expect(screen.getByRole('button', { name: /Create account/ })).toBeEnabled();
  });
  it('shows backend field errors', async () => {
    vi.mocked(registerAccount).mockRejectedValue(new ApiError('Check your password', 400, { password: 'Use a different password' }));
    render(<SignupPage />);
    const user = await completeForm();
    await user.click(screen.getByRole('button', { name: /Create account/ }));
    expect(await screen.findByText('Use a different password')).toBeInTheDocument();
  });
  it('allows retrying invitation loading after a network failure', async () => {
    vi.mocked(resolveInvitation).mockRejectedValueOnce(new ApiError('Cannot reach DRIFT', 0));
    render(<SignupPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach DRIFT');
    await userEvent.click(screen.getByRole('button', { name: 'Retry invitation' }));
    expect(await screen.findByLabelText('Full name')).toBeInTheDocument();
  });
  it('disables repeated submission while the request is pending', async () => {
    let finish: (value: Awaited<ReturnType<typeof registerAccount>>) => void = () => { throw new Error('Request has not started'); };
    vi.mocked(registerAccount).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    render(<SignupPage />);
    const user = await completeForm();
    await user.dblClick(screen.getByRole('button', { name: /Create account/ }));
    expect(registerAccount).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: /Creating your account/ })).toBeDisabled();
    finish({ id: 1, email: invitation.email, fullName: 'Alice', role: invitation.role });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('created'));
  });
  it('says a withdrawn invitation is no longer valid without mentioning an account', async () => {
    vi.mocked(resolveInvitation).mockRejectedValue(new ApiError('This invitation is no longer valid.', 400));
    render(<SignupPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('This invitation is no longer valid.');
    expect(screen.getByRole('alert').textContent).not.toMatch(/account exists|already exists/i);
    expect(screen.queryByLabelText('Full name')).not.toBeInTheDocument();
    expect(registerAccount).not.toHaveBeenCalled();
  });
  it('offers both signup paths when no invitation link was supplied', async () => {
    window.history.replaceState(null, '', '/signup');
    render(<SignupPage />);
    expect(screen.getByRole('button', { name: /Create a company/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Join an existing company/ })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Invitation link or code/)).not.toBeInTheDocument();
    expect(resolveInvitation).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: /Create a company/ }));
    expect(screen.getByLabelText('Company code')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Back to the signup choices/ }));
    expect(screen.getByRole('button', { name: /Join an existing company/ })).toBeInTheDocument();
  });
  it('explains that joining needs an invitation and accepts a pasted invitation link', async () => {
    window.history.replaceState(null, '', '/signup');
    const user = userEvent.setup();
    render(<SignupPage />);
    await user.click(screen.getByRole('button', { name: /Join an existing company/ }));
    expect(screen.getByText(/An invitation from your company administrator is required/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Invitation link or code'), `https://drift.example/signup#invite=${'b'.repeat(43)}`);
    await user.click(screen.getByRole('button', { name: /Continue with invitation/ }));
    expect(resolveInvitation).toHaveBeenCalledWith('b'.repeat(43));
    expect(await screen.findByText(invitation.companyName)).toBeInTheDocument();
    expect(screen.getByLabelText(/Email/)).toHaveAttribute('readonly');
    expect(screen.getByLabelText(/Email/)).toHaveValue(invitation.email);
    expect(screen.getByLabelText(/Your role/)).toHaveAttribute('readonly');
  });
  it('accepts a bare invitation code on the join path', async () => {
    window.history.replaceState(null, '', '/signup');
    const user = userEvent.setup();
    render(<SignupPage />);
    await user.click(screen.getByRole('button', { name: /Join an existing company/ }));
    await user.type(screen.getByLabelText('Invitation link or code'), ` ${'c'.repeat(43)} `);
    await user.click(screen.getByRole('button', { name: /Continue with invitation/ }));
    expect(resolveInvitation).toHaveBeenCalledWith('c'.repeat(43));
  });
});
