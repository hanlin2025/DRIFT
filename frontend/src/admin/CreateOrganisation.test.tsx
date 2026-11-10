import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { CreateOrganisation } from './CreateOrganisation';
import { createOrganisation } from './api';

vi.mock('./api', () => ({ createOrganisation: vi.fn() }));

beforeEach(() => {
  vi.mocked(createOrganisation).mockReset();
});

describe('add an organisation', () => {
  it('does not submit empty fields', async () => {
    const user = userEvent.setup();
    render(<CreateOrganisation token="admin-token" onSessionEnded={() => undefined} />);

    expect(screen.getByRole('button', { name: /Add organisation/ })).toBeDisabled();
    await user.type(screen.getByLabelText('Organisation code'), '   ');
    expect(screen.getByRole('button', { name: /Add organisation/ })).toBeDisabled();
    expect(createOrganisation).not.toHaveBeenCalled();
  });

  it('adds one organisation and shows the stored identity', async () => {
    vi.mocked(createOrganisation).mockResolvedValue({ id: 8, code: 'PACIFIC_GATE', name: 'Pacific Gate Imports' });
    const user = userEvent.setup();
    render(<CreateOrganisation token="admin-token" onSessionEnded={() => undefined} />);

    await user.type(screen.getByLabelText('Organisation code'), ' pacific_gate ');
    await user.type(screen.getByLabelText('Organisation name'), ' Pacific Gate Imports ');
    await user.click(screen.getByRole('button', { name: /Add organisation/ }));

    expect(createOrganisation).toHaveBeenCalledTimes(1);
    expect(createOrganisation).toHaveBeenCalledWith('admin-token', 'pacific_gate', 'Pacific Gate Imports');
    expect(await screen.findByRole('status')).toHaveTextContent('Pacific Gate Imports (PACIFIC_GATE) was added.');
    expect(screen.getByLabelText('Organisation code')).toHaveValue('');
    expect(screen.getByLabelText('Organisation name')).toHaveValue('');
  });

  it('keeps the entered identity when the code already exists', async () => {
    vi.mocked(createOrganisation).mockRejectedValue(new ApiError('An organisation with this code already exists.', 409));
    const user = userEvent.setup();
    render(<CreateOrganisation token="admin-token" onSessionEnded={() => undefined} />);

    await user.type(screen.getByLabelText('Organisation code'), 'HARBOURLINE_DEMO');
    await user.type(screen.getByLabelText('Organisation name'), 'Replacement');
    await user.click(screen.getByRole('button', { name: /Add organisation/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('An organisation with this code already exists.');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Organisation code')).toHaveValue('HARBOURLINE_DEMO');
    expect(createOrganisation).toHaveBeenCalledTimes(1);
  });

  it('shows a forbidden response without adding another organisation', async () => {
    vi.mocked(createOrganisation).mockRejectedValue(new ApiError('Only an administrator can assign users', 403));
    const user = userEvent.setup();
    render(<CreateOrganisation token="admin-token" onSessionEnded={() => undefined} />);

    await user.type(screen.getByLabelText('Organisation code'), 'PACIFIC_GATE');
    await user.type(screen.getByLabelText('Organisation name'), 'Pacific Gate Imports');
    await user.click(screen.getByRole('button', { name: /Add organisation/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Only an administrator can assign users');
    expect(createOrganisation).toHaveBeenCalledTimes(1);
  });

  it('retries a connection failure only when asked', async () => {
    vi.mocked(createOrganisation)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce({ id: 8, code: 'PACIFIC_GATE', name: 'Pacific Gate Imports' });
    const user = userEvent.setup();
    render(<CreateOrganisation token="admin-token" onSessionEnded={() => undefined} />);

    await user.type(screen.getByLabelText('Organisation code'), 'PACIFIC_GATE');
    await user.type(screen.getByLabelText('Organisation name'), 'Pacific Gate Imports');
    await user.click(screen.getByRole('button', { name: /Add organisation/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT. Check your connection and try again.');
    expect(createOrganisation).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Pacific Gate Imports (PACIFIC_GATE) was added.');
    expect(createOrganisation).toHaveBeenCalledTimes(2);
  });

  it('ends the session when the request is no longer signed in', async () => {
    vi.mocked(createOrganisation).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    const ended = vi.fn();
    const user = userEvent.setup();
    render(<CreateOrganisation token="admin-token" onSessionEnded={ended} />);

    await user.type(screen.getByLabelText('Organisation code'), 'PACIFIC_GATE');
    await user.type(screen.getByLabelText('Organisation name'), 'Pacific Gate Imports');
    await user.click(screen.getByRole('button', { name: /Add organisation/ }));

    expect(ended).toHaveBeenCalledTimes(1);
  });
});
