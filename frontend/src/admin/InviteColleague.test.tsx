import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { InviteColleague } from './InviteColleague';
import { inviteColleague } from './api';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  inviteColleague: vi.fn(),
}));

const organisations = [{ id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' }];
const roles = [
  { code: 'ADMIN' as const, label: 'Administrator' },
  { code: 'FREIGHT_FORWARDER' as const, label: 'Freight forwarder' },
];

beforeEach(() => {
  vi.resetAllMocks();
});

describe('invite a colleague', () => {
  it('shows when no organisation can receive an invitation', () => {
    render(<InviteColleague token="token" organisations={[]} roles={roles} onSessionEnded={vi.fn()} />);
    expect(screen.getByRole('status')).toHaveTextContent('No eligible organisation');
    expect(screen.queryByRole('button', { name: 'Send invitation' })).not.toBeInTheDocument();
  });

  it('requires an email, organisation and role before sending', async () => {
    const user = userEvent.setup();
    render(<InviteColleague token="token" organisations={organisations} roles={roles} onSessionEnded={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Send invitation' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter an email, organisation and role.');
    expect(inviteColleague).not.toHaveBeenCalled();
    expect(screen.queryByRole('option', { name: 'Administrator' })).not.toBeInTheDocument();
  });

  it('shows success without a token and keeps a delivery failure visible', async () => {
    vi.mocked(inviteColleague)
      .mockResolvedValueOnce({ email: 'guest@example.com', role: 'FREIGHT_FORWARDER', organisation: organisations[0], expiresAt: '2030-01-08T00:00:00Z' })
      .mockRejectedValueOnce(new ApiError('The invitation email could not be sent. No invitation was issued.', 502));
    const user = userEvent.setup();
    render(<InviteColleague token="token" organisations={organisations} roles={roles} onSessionEnded={vi.fn()} />);
    await user.type(screen.getByLabelText('Colleague email'), 'guest@example.com');
    await user.selectOptions(screen.getByLabelText('Invitation organisation'), '1');
    await user.selectOptions(screen.getByLabelText('Invitation role'), 'FREIGHT_FORWARDER');
    await user.click(screen.getByRole('button', { name: 'Send invitation' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Invitation sent to guest@example.com');
    expect(screen.getByRole('status').textContent).not.toMatch(/token|code/i);
    await user.type(screen.getByLabelText('Colleague email'), 'again@example.com');
    await user.selectOptions(screen.getByLabelText('Invitation organisation'), '1');
    await user.selectOptions(screen.getByLabelText('Invitation role'), 'FREIGHT_FORWARDER');
    await user.click(screen.getByRole('button', { name: 'Send invitation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be sent');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
