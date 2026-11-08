import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { PendingInvitations } from './PendingInvitations';
import { listPendingInvitations, withdrawInvitation } from './api';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  listPendingInvitations: vi.fn(),
  withdrawInvitation: vi.fn(),
}));

const roles = [
  { code: 'FREIGHT_FORWARDER' as const, label: 'Freight forwarder' },
  { code: 'IMPORTER' as const, label: 'Importer' },
];
const organisation = { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' };

beforeEach(() => {
  vi.resetAllMocks();
});

describe('pending invitations', () => {
  it('shows an empty list without treating it as an error', async () => {
    vi.mocked(listPendingInvitations).mockResolvedValue([]);
    render(<PendingInvitations token="token" roles={roles} onSessionEnded={vi.fn()} />);
    expect(await screen.findByRole('status')).toHaveTextContent('No pending invitations.');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the invited email, role, organisation and expiry, and not a token', async () => {
    vi.mocked(listPendingInvitations).mockResolvedValue([
      { id: 4, email: 'guest@example.com', role: 'FREIGHT_FORWARDER', organisation, expiresAt: '2030-01-08T00:00:00Z', expired: false },
      { id: 5, email: 'late@example.com', role: 'IMPORTER', organisation, expiresAt: '2020-01-01T00:00:00Z', expired: true },
    ]);
    render(<PendingInvitations token="token" roles={roles} onSessionEnded={vi.fn()} />);
    expect(await screen.findByText('guest@example.com')).toBeInTheDocument();
    expect(screen.getByText('Freight forwarder at Harbourline Logistics (Demo)')).toBeInTheDocument();
    expect(screen.getByText('Pending')).toBeInTheDocument();
    expect(screen.getByText('late@example.com')).toBeInTheDocument();
    expect(screen.getByText('Expired')).toBeInTheDocument();
    expect(screen.getAllByText(/Expires/)).toHaveLength(2);
    expect(document.body.textContent).not.toContain('plain-invitation-token');
  });

  it('lets a failed load be retried and does not change an invitation', async () => {
    vi.mocked(listPendingInvitations)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce([]);
    const user = userEvent.setup();
    render(<PendingInvitations token="token" roles={roles} onSessionEnded={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('could not reach DRIFT');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('status')).toHaveTextContent('No pending invitations.');
    expect(listPendingInvitations).toHaveBeenCalledTimes(2);
    expect(withdrawInvitation).not.toHaveBeenCalled();
  });

  it('asks for confirmation before withdrawing and keeps a failed withdrawal pending', async () => {
    const pending = { id: 4, email: 'guest@example.com', role: 'FREIGHT_FORWARDER' as const, organisation, expiresAt: '2030-01-08T00:00:00Z', expired: false };
    vi.mocked(listPendingInvitations).mockResolvedValue([pending]);
    vi.mocked(withdrawInvitation)
      .mockRejectedValueOnce(new ApiError('The invitation could not be withdrawn.', 503))
      .mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    render(<PendingInvitations token="token" roles={roles} onSessionEnded={vi.fn()} />);
    await user.click(await screen.findByRole('button', { name: 'Withdraw invitation for guest@example.com' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('They will not be able to create an account');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(withdrawInvitation).not.toHaveBeenCalled();
    expect(screen.getByText('guest@example.com')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Withdraw invitation for guest@example.com' }));
    await user.click(screen.getByRole('button', { name: 'Withdraw', exact: true }));
    expect(await screen.findByRole('alert')).toHaveTextContent('still pending');
    expect(screen.getByText('guest@example.com')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();

    vi.mocked(listPendingInvitations).mockResolvedValue([]);
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Invitation for guest@example.com withdrawn.')).toBeInTheDocument();
    expect(withdrawInvitation).toHaveBeenCalledTimes(2);
    expect(withdrawInvitation).toHaveBeenCalledWith('token', 4);
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Withdraw invitation for guest@example.com' })).not.toBeInTheDocument());
  });

  it('explains an expired invitation and leaves it expired', async () => {
    const expired = { id: 5, email: 'late@example.com', role: 'IMPORTER' as const, organisation, expiresAt: '2020-01-01T00:00:00Z', expired: true };
    vi.mocked(listPendingInvitations).mockResolvedValue([expired]);
    vi.mocked(withdrawInvitation).mockRejectedValue(new ApiError('This invitation has expired.', 409));
    const user = userEvent.setup();
    render(<PendingInvitations token="token" roles={roles} onSessionEnded={vi.fn()} />);
    await user.click(await screen.findByRole('button', { name: 'Withdraw invitation for late@example.com' }));
    await user.click(screen.getByRole('button', { name: 'Withdraw', exact: true }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This invitation has expired.');
    expect(screen.getByText('Expired')).toBeInTheDocument();
    expect(screen.getByText('late@example.com')).toBeInTheDocument();
  });
});
