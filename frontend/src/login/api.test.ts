import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadSession, login } from './api';

const company = { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' };

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function account(role: string, token = 'login-token') {
  return {
    token,
    id: 4,
    fullName: 'Alice Tan',
    email: 'alice@example.com',
    role,
    expiresAt: '2099-01-01T00:00:00Z',
    company,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('login and session roles', () => {
  it.each(['IMPORTER', 'FREIGHT_FORWARDER', 'ADMIN', 'LOGISTICS_MANAGER'])('accepts a %s login', async role => {
    const fetchMock = vi.fn().mockResolvedValue(json(account(role)));
    vi.stubGlobal('fetch', fetchMock);
    const session = await login('alice@example.com', 'Example123');
    expect(session.role).toBe(role);
    expect(session.token).toBe('login-token');
    expect(fetchMock).toHaveBeenCalledWith('/api/login', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'alice@example.com', password: 'Example123' }),
    }));
  });

  it.each(['ADMIN', 'LOGISTICS_MANAGER'])('loads a saved %s session with the existing token', async role => {
    const fetchMock = vi.fn().mockResolvedValue(json(account(role, 'ignored-token')));
    vi.stubGlobal('fetch', fetchMock);
    const session = await loadSession('saved-token');
    expect(session.role).toBe(role);
    expect(session.token).toBe('saved-token');
    expect(fetchMock).toHaveBeenCalledWith('/api/session', expect.objectContaining({
      headers: { Authorization: 'Bearer saved-token' },
    }));
  });

  it('rejects a session whose role is not one of the four account roles', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(account('PLANNER'))));
    await expect(loadSession('saved-token')).rejects.toMatchObject({ status: 502 });
  });
});
