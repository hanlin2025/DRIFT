import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { SetupPage } from './SetupPage';
import { createFirstAdministrator, loadSetupStatus } from './api';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  loadSetupStatus: vi.fn(),
  createFirstAdministrator: vi.fn(),
}));

const organisation = { id: 2, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' };
const credential = 'test-only-setup-token-not-used-outside-tests';

function page() {
  return render(<MemoryRouter><SetupPage /></MemoryRouter>);
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(loadSetupStatus).mockResolvedValue({ initialized: false, organisations: [organisation] });
  vi.mocked(createFirstAdministrator).mockResolvedValue({
    id: 1, fullName: 'Ada Admin', email: 'ada@example.com', role: 'ADMIN', organisation,
  });
});

async function openForm() {
  const user = userEvent.setup();
  page();
  await user.type(screen.getByLabelText('Setup credential'), credential);
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  expect(await screen.findByLabelText('Organisation')).toBeInTheDocument();
  return user;
}

describe('first-administrator setup form', () => {
  it('asks for the operator credential before showing organisations', async () => {
    const user = userEvent.setup();
    page();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter the setup credential.');
    expect(loadSetupStatus).not.toHaveBeenCalled();
  });

  it('shows an invalid credential without opening the form', async () => {
    vi.mocked(loadSetupStatus).mockRejectedValue(new ApiError('The setup credential is missing or invalid.', 401));
    const user = userEvent.setup();
    page();
    await user.type(screen.getByLabelText('Setup credential'), 'wrong-credential');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('missing or invalid');
    expect(screen.queryByLabelText('Organisation')).not.toBeInTheDocument();
  });

  it('shows that setup is already complete', async () => {
    vi.mocked(loadSetupStatus).mockResolvedValue({ initialized: true, organisations: [] });
    const user = userEvent.setup();
    page();
    await user.type(screen.getByLabelText('Setup credential'), credential);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('status')).toHaveTextContent('already has an administrator');
    expect(screen.queryByRole('button', { name: /Create administrator/ })).not.toBeInTheDocument();
  });

  it('shows when no organisation can be chosen', async () => {
    vi.mocked(loadSetupStatus).mockResolvedValue({ initialized: false, organisations: [] });
    const user = userEvent.setup();
    page();
    await user.type(screen.getByLabelText('Setup credential'), credential);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('status')).toHaveTextContent('No eligible organisation');
  });

  it('blocks an incomplete administrator and then creates one', async () => {
    const user = await openForm();
    await user.click(screen.getByRole('button', { name: /Create administrator/ }));
    expect(screen.getByText('Choose an organisation.')).toBeInTheDocument();
    expect(createFirstAdministrator).not.toHaveBeenCalled();
    await user.selectOptions(screen.getByLabelText('Organisation'), '2');
    await user.type(screen.getByLabelText('Full name'), 'Ada Admin');
    await user.type(screen.getByLabelText('Work email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password', { exact: true }), 'Example123');
    await user.type(screen.getByLabelText('Confirm password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Create administrator/ }));
    expect(createFirstAdministrator).toHaveBeenCalledExactlyOnceWith(credential, {
      organisationId: 2, fullName: 'Ada Admin', email: 'ada@example.com', password: 'Example123',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Ada Admin is the administrator');
    expect(screen.queryByLabelText('Password', { exact: true })).not.toBeInTheDocument();
  });

  it('keeps the form when creation is rejected', async () => {
    vi.mocked(createFirstAdministrator).mockRejectedValue(new ApiError('Choose an active organisation.', 400, {
      organisationId: 'Choose an active organisation.',
    }));
    const user = await openForm();
    await user.selectOptions(screen.getByLabelText('Organisation'), '2');
    await user.type(screen.getByLabelText('Full name'), 'Ada Admin');
    await user.type(screen.getByLabelText('Work email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password', { exact: true }), 'Example123');
    await user.type(screen.getByLabelText('Confirm password'), 'Example123');
    await user.click(screen.getByRole('button', { name: /Create administrator/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose an active organisation.');
    expect(screen.getByRole('button', { name: /Create administrator/ })).toBeInTheDocument();
  });
});
