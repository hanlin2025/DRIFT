import { afterEach, describe, expect, it, vi } from 'vitest';
import { registerAccount, resolveInvitation } from './api';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('invitation and registration roles', () => {
  it.each(['IMPORTER', 'FREIGHT_FORWARDER'] as const)('still accepts an invitation for %s', async role => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      email: 'alice@example.com',
      companyName: 'Harbourline Logistics (Demo)',
      role,
      expiresAt: '2030-01-01T00:00:00Z',
    })));
    await expect(resolveInvitation('invite-token')).resolves.toMatchObject({ role });
  });

  it.each(['ADMIN', 'LOGISTICS_MANAGER'])('rejects an invitation for %s', async role => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      email: 'alice@example.com',
      companyName: 'Harbourline Logistics (Demo)',
      role,
      expiresAt: '2030-01-01T00:00:00Z',
    })));
    await expect(resolveInvitation('invite-token')).rejects.toMatchObject({ status: 502 });
  });

  it.each(['IMPORTER', 'FREIGHT_FORWARDER'] as const)('still accepts registration as %s', async role => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 4,
      fullName: 'Alice Tan',
      email: 'alice@example.com',
      role,
    })));
    await expect(registerAccount({
      fullName: 'Alice Tan',
      email: 'alice@example.com',
      password: 'Example123',
      invitationToken: 'invite-token',
    })).resolves.toMatchObject({ role });
  });

  it.each(['ADMIN', 'LOGISTICS_MANAGER'])('rejects registration as %s', async role => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 4,
      fullName: 'Alice Tan',
      email: 'alice@example.com',
      role,
    })));
    await expect(registerAccount({
      fullName: 'Alice Tan',
      email: 'alice@example.com',
      password: 'Example123',
      invitationToken: 'invite-token',
    })).rejects.toMatchObject({ status: 502 });
  });
});
