import { afterEach, describe, expect, it, vi } from 'vitest';
import { getProfile, profileUpdateBody, updateProfile } from './api';

const profile = {
  id: 4,
  fullName: 'Alice Tan',
  email: 'alice@example.com',
  role: 'FREIGHT_FORWARDER',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
  riskEmailNotificationsEnabled: false,
  minimumRiskNotificationLevel: 'HIGH',
};

afterEach(() => vi.unstubAllGlobals());

describe('profile API', () => {
  it('omits a password change when both password fields are empty', () => {
    expect(profileUpdateBody({
      fullName: 'Alice Tan',
      riskEmailNotificationsEnabled: true,
      minimumRiskNotificationLevel: 'CRITICAL',
      currentPassword: '',
      newPassword: '',
    })).toEqual({
      fullName: 'Alice Tan',
      riskEmailNotificationsEnabled: true,
      minimumRiskNotificationLevel: 'CRITICAL',
    });
  });

  it('sends both password fields when either one is present', () => {
    expect(profileUpdateBody({
      fullName: 'Alice Tan',
      riskEmailNotificationsEnabled: false,
      minimumRiskNotificationLevel: 'HIGH',
      currentPassword: 'Example123',
      newPassword: '',
    })).toMatchObject({ currentPassword: 'Example123', newPassword: '' });
  });

  it('reads the signed-in profile and does not ask for another user', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(profile), {
      status: 200, headers: { 'content-type': 'application/json' },
    })));
    await expect(getProfile('token')).resolves.toMatchObject({ email: 'alice@example.com', company: { name: 'Harbourline Logistics (Demo)' } });
    expect(fetch).toHaveBeenCalledWith('/api/profile', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer token' }),
    }));
  });

  it('patches only the name, notification choice, and a password when one was typed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...profile, fullName: 'Alicia Tan' }), {
      status: 200, headers: { 'content-type': 'application/json' },
    })));
    await updateProfile('token', {
      fullName: 'Alicia Tan',
      riskEmailNotificationsEnabled: true,
      minimumRiskNotificationLevel: 'CRITICAL',
      currentPassword: 'Example123',
      newPassword: 'FreshPass1',
    });
    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect(JSON.parse(String(init?.body))).toEqual({
      fullName: 'Alicia Tan',
      riskEmailNotificationsEnabled: true,
      minimumRiskNotificationLevel: 'CRITICAL',
      currentPassword: 'Example123',
      newPassword: 'FreshPass1',
    });
    expect(init?.method).toBe('PATCH');
  });
});
