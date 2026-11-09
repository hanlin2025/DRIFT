import { ApiError } from '../signup/api';

export type SetupOrganisation = { id: number; code: string; name: string };
export type SetupStatus = { initialized: boolean; organisations: SetupOrganisation[] };
export type SetupAccount = {
  id: number;
  fullName: string;
  email: string;
  role: 'ADMIN';
  organisation: SetupOrganisation;
};
export type SetupDraft = { organisationId: number; fullName: string; email: string; password: string };

export const SETUP_CREDENTIAL_HEADER = 'X-DRIFT-Setup-Token';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function organisationOf(value: unknown): SetupOrganisation {
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.code !== 'string' || typeof value.name !== 'string') {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  }
  return { id: value.id, code: value.code, name: value.name };
}

async function send(path: string, method: string, credential: string, body?: unknown): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', [SETUP_CREDENTIAL_HEADER]: credential },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
      cache: 'no-store',
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

export async function loadSetupStatus(credential: string): Promise<SetupStatus> {
  const data = await send('/api/setup/status', 'GET', credential);
  if (!isRecord(data) || typeof data.initialized !== 'boolean' || !Array.isArray(data.organisations)) {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  }
  return { initialized: data.initialized, organisations: data.organisations.map(organisationOf) };
}

export async function createFirstAdministrator(credential: string, draft: SetupDraft): Promise<SetupAccount> {
  const data = await send('/api/setup/administrator', 'POST', credential, draft);
  if (!isRecord(data) || typeof data.id !== 'number' || typeof data.fullName !== 'string' || typeof data.email !== 'string'
    || data.role !== 'ADMIN' || !('organisation' in data)) {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  }
  const account = {
    id: data.id,
    fullName: data.fullName,
    email: data.email,
    role: 'ADMIN' as const,
    organisation: organisationOf(data.organisation),
  };
  if ('password' in data || 'token' in data || SETUP_CREDENTIAL_HEADER in data) {
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  }
  return account;
}
