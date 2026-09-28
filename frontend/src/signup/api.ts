export type Role = 'IMPORTER' | 'FREIGHT_FORWARDER';
export type Invitation = { email: string; companyName: string; role: Role; expiresAt: string };
export type Registration = { fullName: string; email: string; password: string; invitationToken: string };
export type Account = { id: number; fullName: string; email: string; role: Role };

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly fields: Record<string, string> = {}) {
    super(message);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export async function post(path: string, body: unknown): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: AbortSignal.timeout(15000), cache: 'no-store',
    });
  } catch (error) {
    if (error instanceof TypeError || (error instanceof DOMException && ['TimeoutError', 'AbortError'].includes(error.name))) {
      throw new ApiError('We could not reach DRIFT. Check your connection and try again.', 0);
    }
    throw error;
  }
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', response.status);
  }
  const data: unknown = await response.json();
  if (!response.ok) {
    const fields: Record<string, string> = {};
    if (isRecord(data) && isRecord(data.errors)) {
      for (const [key, value] of Object.entries(data.errors)) if (typeof value === 'string') fields[key] = value;
    }
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status, fields);
  }
  return data;
}

export async function resolveInvitation(token: string): Promise<Invitation> {
  const data = await post('/api/invitations/resolve', { token });
  if (!isRecord(data) || typeof data.email !== 'string' || typeof data.companyName !== 'string'
    || (data.role !== 'IMPORTER' && data.role !== 'FREIGHT_FORWARDER') || typeof data.expiresAt !== 'string') {
    throw new ApiError('DRIFT could not read this invitation. Please try again.', 502);
  }
  return { email: data.email, companyName: data.companyName, role: data.role, expiresAt: data.expiresAt };
}

export async function registerAccount(registration: Registration): Promise<Account> {
  const data = await post('/api/register', registration);
  if (!isRecord(data) || typeof data.id !== 'number' || typeof data.email !== 'string'
    || typeof data.fullName !== 'string' || (data.role !== 'IMPORTER' && data.role !== 'FREIGHT_FORWARDER')) {
    throw new ApiError('We could not confirm your registration. Check with your administrator before trying again.', 502);
  }
  return { id: data.id, email: data.email, fullName: data.fullName, role: data.role };
}
