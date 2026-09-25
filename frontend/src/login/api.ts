import { ApiError, post, type Role } from '../signup/api';
import { type Session } from '../session/session';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isRole(value: unknown): value is Role {
  return value === 'IMPORTER' || value === 'FREIGHT_FORWARDER';
}

function companyOf(value: unknown): Session['company'] {
  if (value === null) return null;
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.code !== 'string' || typeof value.name !== 'string') {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  }
  return { id: value.id, code: value.code, name: value.name };
}

function accountOf(data: unknown, knownToken: string | null): Session {
  if (!isRecord(data) || typeof data.id !== 'number' || typeof data.fullName !== 'string'
    || typeof data.email !== 'string' || !isRole(data.role) || typeof data.expiresAt !== 'string'
    || !('company' in data)) {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  }
  const token = knownToken ?? (typeof data.token === 'string' ? data.token : null);
  if (!token) throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  return {
    token,
    expiresAt: data.expiresAt,
    id: data.id,
    fullName: data.fullName,
    email: data.email,
    role: data.role,
    company: companyOf(data.company),
  };
}

export async function login(email: string, password: string): Promise<Session> {
  const data = await post('/api/login', { email, password });
  const session = accountOf(data, null);
  if (!session.token) throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  return session;
}

export async function loadSession(token: string): Promise<Session> {
  let response: Response;
  try {
    response = await fetch('/api/session', {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(15000),
      cache: 'no-store',
    });
  } catch (error) {
    if (error instanceof TypeError || (error instanceof DOMException && ['TimeoutError', 'AbortError'].includes(error.name))) {
      throw new ApiError('We could not reach DRIFT. Check your connection and try again.', 0);
    }
    throw error;
  }
  if (response.status === 401) throw new ApiError('Your session has ended. Log in again.', 401);
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', response.status);
  }
  const data: unknown = await response.json();
  if (!response.ok) {
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status);
  }
  const session = accountOf(data, token);
  return { ...session, token };
}
