import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { PendingInvitations } from './PendingInvitations';
import { listPendingInvitations } from './api';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  listPendingInvitations: vi.fn(),
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
    expect(screen.getByText(/Expires/)).toBeInTheDocument();
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
  });
});
