import { beforeEach, describe, expect, it } from 'vitest';
import { clearSession, readSession, writeSession, type Session } from './session';

const profile: Session = {
  token: '',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 4,
  fullName: 'Casey Guest',
  email: 'casey@example.com',
  role: 'LOGISTICS_MANAGER',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
};

beforeEach(() => {
  clearSession();
});

describe('browser session', () => {
  it('keeps a signed-in profile available to a new tab without the bearer token', () => {
    writeSession(profile);
    sessionStorage.clear();
    expect(readSession()).toMatchObject({ role: 'LOGISTICS_MANAGER', company: { name: 'Harbourline Logistics (Demo)' }, token: '' });
    expect(localStorage.getItem('drift.profile')).not.toContain('eyJ');
  });

  it('does not copy a bearer token into shared storage', () => {
    writeSession({ ...profile, token: 'session-token' });
    expect(sessionStorage.getItem('drift.session')).toContain('session-token');
    expect(localStorage.getItem('drift.profile')).toBeNull();
  });

  it('sign-out removes the profile from the current tab and from shared storage', () => {
    writeSession(profile);
    clearSession();
    sessionStorage.setItem('drift.session', JSON.stringify(profile));
    clearSession();
    expect(sessionStorage.getItem('drift.session')).toBeNull();
    expect(localStorage.getItem('drift.profile')).toBeNull();
    expect(readSession()).toBeNull();
  });
});
