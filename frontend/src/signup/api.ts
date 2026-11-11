export type Role = 'IMPORTER' | 'FREIGHT_FORWARDER' | 'LOGISTICS_MANAGER';
export type Invitation = { email: string; companyName: string; role: Role; expiresAt: string };
export type Registration = { fullName: string; email: string; password: string; invitationToken: string };
export type Account = { id: number; fullName: string; email: string; role: Role };

export class ApiError extends Error {
  /** `errorCode` is the machine-readable `code` marker from the server, such as `EMAIL_NOT_VERIFIED`, when it sends one. */
  constructor(message: string, readonly status: number, readonly fields: Record<string, string> = {}, readonly errorCode?: string) {
    super(message);
  }
}

function isInvitedRole(value: unknown): value is Role {
  return value === 'IMPORTER' || value === 'FREIGHT_FORWARDER' || value === 'LOGISTICS_MANAGER';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export async function post(path: string, body: unknown): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-DRIFT-Request': '1' },
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
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status, fields,
      isRecord(data) && typeof data.code === 'string' ? data.code : undefined);
  }
  return data;
}

export async function resolveInvitation(token: string): Promise<Invitation> {
  const data = await post('/api/invitations/resolve', { token });
  if (!isRecord(data) || typeof data.email !== 'string' || typeof data.companyName !== 'string'
    || !isInvitedRole(data.role) || typeof data.expiresAt !== 'string') {
    throw new ApiError('DRIFT could not read this invitation. Please try again.', 502);
  }
  return { email: data.email, companyName: data.companyName, role: data.role, expiresAt: data.expiresAt };
}

export async function registerAccount(registration: Registration): Promise<Account> {
  const data = await post('/api/register', registration);
  if (!isRecord(data) || typeof data.id !== 'number' || typeof data.email !== 'string'
    || typeof data.fullName !== 'string' || !isInvitedRole(data.role)) {
    throw new ApiError('We could not confirm your registration. Check with your administrator before trying again.', 502);
  }
  return { id: data.id, email: data.email, fullName: data.fullName, role: data.role };
}

export type CompanySignup = { companyCode: string; companyName: string; fullName: string; email: string; password: string };

function messageOf(data: unknown, fallback: string): { message: string } {
  if (!isRecord(data) || typeof data.message !== 'string') throw new ApiError(fallback, 502);
  return { message: data.message };
}

export async function registerCompany(input: CompanySignup): Promise<{ message: string }> {
  return messageOf(await post('/api/register/company', input), 'We could not confirm your signup. Please try again.');
}

export async function verifyEmail(token: string): Promise<{ message: string }> {
  return messageOf(await post('/api/auth/verify-email', { token }), 'We could not confirm your email. Please try again.');
}

export async function resendVerification(email: string): Promise<{ message: string }> {
  return messageOf(await post('/api/auth/resend-verification', { email }), 'We could not send a new link. Please try again.');
}
