import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { ApiError } from '../signup/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { loadSession } from '../login/api';
import { listShipments } from '../shipment/api';
import { getProfile, updateProfile, type Profile } from './api';
import { ProfilePage } from './ProfilePage';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ listShipments: vi.fn(), getCdg201ArrivalEvidence: vi.fn() }));
vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api');
  return { ...actual, getProfile: vi.fn(), updateProfile: vi.fn() };
});

const session: Session = {
  token: 'session-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 4,
  fullName: 'Alice Tan',
  email: 'alice@example.com',
  role: 'FREIGHT_FORWARDER',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
};

const profile: Profile = {
  id: 4,
  fullName: 'Alice Tan',
  email: 'alice@example.com',
  role: 'FREIGHT_FORWARDER',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
  riskEmailNotificationsEnabled: false,
  minimumRiskNotificationLevel: 'HIGH',
};

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(loadSession).mockImplementation(async token => ({ ...session, token }));
  vi.mocked(listShipments).mockResolvedValue([]);
  vi.mocked(getProfile).mockResolvedValue(profile);
  vi.mocked(updateProfile).mockImplementation(async (_token, update) => ({
    ...profile,
    fullName: update.fullName.trim() || profile.fullName,
    riskEmailNotificationsEnabled: update.riskEmailNotificationsEnabled,
    minimumRiskNotificationLevel: update.minimumRiskNotificationLevel,
  }));
});

describe('profile page', () => {
  it('shows the signed-in account and keeps email, company and role read-only', async () => {
    const user = userEvent.setup();
    render(<ProfilePage token="session-token" onSessionEnded={vi.fn()} onPasswordChanged={vi.fn()} onNameSaved={vi.fn()} />);
    expect(await screen.findByRole('heading', { name: 'Profile' })).toBeInTheDocument();
    expect(screen.getByLabelText('Full name')).toHaveValue('Alice Tan');
    expect(screen.getByLabelText('Email')).toHaveValue('alice@example.com');
    expect(screen.getByLabelText('Email')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Company')).toHaveValue('Harbourline Logistics (Demo)');
    expect(screen.getByLabelText('Company')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Role')).toHaveValue('Freight forwarder');
    expect(screen.getByLabelText('Role')).toHaveAttribute('readonly');
    expect(screen.getByRole('checkbox', { name: 'Email me when a shipment risk reaches this level' })).not.toBeChecked();
    expect(screen.getByLabelText('Minimum risk level')).toHaveValue('HIGH');
    expect(screen.getByRole('list', { name: 'Password requirements' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/photo/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /send/i })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Email'), 'other@example.com');
    expect(screen.getByLabelText('Email')).toHaveValue('alice@example.com');
  });

  it('saves the name and notification choice without a password', async () => {
    const user = userEvent.setup();
    const onNameSaved = vi.fn();
    render(<ProfilePage token="session-token" onSessionEnded={vi.fn()} onPasswordChanged={vi.fn()} onNameSaved={onNameSaved} />);
    const name = await screen.findByLabelText('Full name');
    await user.clear(name);
    await user.type(name, 'Alicia Tan');
    await user.click(screen.getByRole('checkbox', { name: 'Email me when a shipment risk reaches this level' }));
    await user.selectOptions(screen.getByLabelText('Minimum risk level'), 'CRITICAL');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Profile saved.');
    expect(updateProfile).toHaveBeenCalledWith('session-token', {
      fullName: 'Alicia Tan',
      riskEmailNotificationsEnabled: true,
      minimumRiskNotificationLevel: 'CRITICAL',
    });
    expect(onNameSaved).toHaveBeenCalledWith('Alicia Tan');
  });

  it('shows the API message for a bad name or password and stays on the page', async () => {
    const user = userEvent.setup();
    const onPasswordChanged = vi.fn();
    vi.mocked(updateProfile)
      .mockRejectedValueOnce(new ApiError('Full name is required', 400))
      .mockRejectedValueOnce(new ApiError('Full name must be at most 200 characters', 400))
      .mockRejectedValueOnce(new ApiError('Current password is incorrect.', 400))
      .mockRejectedValueOnce(new ApiError('Password must be at least 8 characters and include an uppercase letter, a lowercase letter, and a number, with no spaces', 400));
    render(<ProfilePage token="session-token" onSessionEnded={vi.fn()} onPasswordChanged={onPasswordChanged} onNameSaved={vi.fn()} />);
    const name = await screen.findByLabelText('Full name');
    await user.clear(name);
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Full name is required');
    await user.type(name, 'n'.repeat(201));
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Full name must be at most 200 characters');
    await user.type(screen.getByLabelText('Current password'), 'WrongPass1');
    await user.type(screen.getByLabelText('New password'), 'FreshPass1');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Current password is incorrect.');
    await user.clear(screen.getByLabelText('New password'));
    await user.type(screen.getByLabelText('New password'), 'password1');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Password must be at least 8 characters');
    expect(onPasswordChanged).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'Profile' })).toBeInTheDocument();
  });

  it('ends the current session after a password change', async () => {
    const user = userEvent.setup();
    const onPasswordChanged = vi.fn();
    render(<ProfilePage token="session-token" onSessionEnded={vi.fn()} onPasswordChanged={onPasswordChanged} onNameSaved={vi.fn()} />);
    await screen.findByLabelText('Full name');
    await user.type(screen.getByLabelText('Current password'), 'Example123');
    await user.type(screen.getByLabelText('New password'), 'FreshPass1');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(onPasswordChanged).toHaveBeenCalledOnce();
    expect(updateProfile).toHaveBeenCalledWith('session-token', expect.objectContaining({
      currentPassword: 'Example123', newPassword: 'FreshPass1',
    }));
  });

  it('opens from the workspace and refuses a signed-out visit', async () => {
    const user = userEvent.setup();
    writeSession(session);
    render(<MemoryRouter initialEntries={['/freight-forwarder']}><AppRoutes /></MemoryRouter>);
    await user.click(await screen.findByRole('link', { name: 'Profile' }));
    expect(await screen.findByRole('heading', { name: 'Profile' })).toBeInTheDocument();
    expect(getProfile).toHaveBeenCalledWith('session-token');
    clearSession();
    render(<MemoryRouter initialEntries={['/profile']}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
  });

  it('returns to login for the new password without calling the session expired', async () => {
    const user = userEvent.setup();
    writeSession(session);
    render(<MemoryRouter initialEntries={['/profile']}><AppRoutes /></MemoryRouter>);
    await screen.findByLabelText('Full name');
    await user.type(screen.getByLabelText('Current password'), 'Example123');
    await user.type(screen.getByLabelText('New password'), 'FreshPass1');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Your password was changed. Log in with the new password.');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });

  it('shows an empty company without an editor', async () => {
    vi.mocked(getProfile).mockResolvedValue({ ...profile, role: 'ADMIN', company: null });
    render(<ProfilePage token="session-token" onSessionEnded={vi.fn()} onPasswordChanged={vi.fn()} onNameSaved={vi.fn()} />);
    expect(await screen.findByLabelText('Company')).toHaveValue('No company assigned');
    expect(screen.getByLabelText('Role')).toHaveValue('Administrator');
  });
});
