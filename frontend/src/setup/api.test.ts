import { afterEach, describe, expect, it, vi } from 'vitest';
import { SETUP_CREDENTIAL_HEADER, createFirstAdministrator, loadSetupStatus } from './api';

const credential = 'test-only-setup-token-not-used-outside-tests';
const organisation = { id: 2, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' };

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

afterEach(() => { vi.unstubAllGlobals(); });

describe('first-administrator setup api', () => {
  it('sends the operator credential as a header and not in the address', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ initialized: false, organisations: [organisation] }));
    vi.stubGlobal('fetch', fetchMock);
    const status = await loadSetupStatus(credential);
    expect(status.organisations).toEqual([organisation]);
    expect(fetchMock).toHaveBeenCalledWith('/api/setup/status', expect.objectContaining({
      method: 'GET',
      headers: expect.objectContaining({ [SETUP_CREDENTIAL_HEADER]: credential }),
    }));
    expect(String(fetchMock.mock.calls[0][0])).not.toContain(credential);
  });

  it('rejects a response that discloses a token or password', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 1, fullName: 'Ada Admin', email: 'ada@example.com', role: 'ADMIN', organisation, token: 'secret-session',
    }, 201)));
    await expect(createFirstAdministrator(credential, {
      organisationId: 2, fullName: 'Ada Admin', email: 'ada@example.com', password: 'Example123',
    })).rejects.toThrow(/unexpected response/i);
  });

  it('keeps a failed setup from looking successful', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'This installation already has an administrator.' }, 409)));
    await expect(createFirstAdministrator(credential, {
      organisationId: 2, fullName: 'Ada Admin', email: 'ada@example.com', password: 'Example123',
    })).rejects.toThrow(/already has an administrator/);
  });
});
