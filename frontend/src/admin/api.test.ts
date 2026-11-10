import { afterEach, describe, expect, it, vi } from 'vitest';
import { listPendingInvitations, withdrawInvitation } from './api';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

const invitation = {
  id: 4,
  email: 'guest@example.com',
  role: 'FREIGHT_FORWARDER',
  organisation: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
  expiresAt: '2030-01-08T00:00:00Z',
  expired: false,
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('invitation management responses', () => {
  it('rejects a pending invitation list that contains a raw token', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([{ ...invitation, token: 'secret-invitation-token' }])));
    await expect(listPendingInvitations('session')).rejects.toMatchObject({ status: 502 });
  });

  it('reads a pending invitation that does not contain a token', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([invitation])));
    await expect(listPendingInvitations('session')).resolves.toEqual([invitation]);
  });

  it('rejects a withdrawal response that contains a raw token', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 4, status: 'REVOKED', code: 'secret-invitation-token' })));
    await expect(withdrawInvitation('session', 4)).rejects.toMatchObject({ status: 502 });
  });

  it('accepts a withdrawal that does not contain a token', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 4, status: 'REVOKED', email: invitation.email })));
    await expect(withdrawInvitation('session', 4)).resolves.toBeUndefined();
  });
});
