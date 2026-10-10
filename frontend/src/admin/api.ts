import { ApiError } from '../signup/api';
import { type Role } from '../session/session';

export type AdminOrganisation = { id: number; code: string; name: string };
export type AdminRole = { code: Role; label: string };
export type AdminUser = {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  organisation: AdminOrganisation | null;
};
export type AssignmentAuditParty = { id: number; fullName: string; email: string };
export type AssignmentAudit = {
  id: number;
  recordedAt: string;
  operator: AssignmentAuditParty;
  target: AssignmentAuditParty;
  previousRole: Role;
  assignedRole: Role;
  previousOrganisation: AdminOrganisation | null;
  organisation: AdminOrganisation;
};

const UNEXPECTED = 'DRIFT returned an unexpected response. Please try again shortly.';
const ROLES: Role[] = ['IMPORTER', 'FREIGHT_FORWARDER', 'ADMIN', 'LOGISTICS_MANAGER'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isRole(value: unknown): value is Role {
  return typeof value === 'string' && ROLES.includes(value as Role);
}

function organisationOf(value: unknown): AdminOrganisation | null {
  if (value == null) return null;
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.code !== 'string' || typeof value.name !== 'string') {
    throw new ApiError(UNEXPECTED, 502);
  }
  return { id: value.id, code: value.code, name: value.name };
}

function partyOf(value: unknown): AssignmentAuditParty {
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.fullName !== 'string' || typeof value.email !== 'string') {
    throw new ApiError(UNEXPECTED, 502);
  }
  return { id: value.id, fullName: value.fullName, email: value.email };
}

function userOf(value: unknown): AdminUser {
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.fullName !== 'string'
    || typeof value.email !== 'string' || !isRole(value.role) || !('organisation' in value)) {
    throw new ApiError(UNEXPECTED, 502);
  }
  return {
    id: value.id,
    fullName: value.fullName,
    email: value.email,
    role: value.role,
    organisation: organisationOf(value.organisation),
  };
}

async function send(path: string, token: string, init: RequestInit = {}): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        ...init.headers,
        Authorization: `Bearer ${token}`,
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      },
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
  if (!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(UNEXPECTED, response.status);
  const data: unknown = await response.json();
  if (!response.ok) {
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status);
  }
  return data;
}

export async function listAssignableUsers(token: string): Promise<AdminUser[]> {
  const data = await send('/api/admin/users', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  return data.map(userOf);
}

export async function listAssignableRoles(token: string): Promise<AdminRole[]> {
  const data = await send('/api/admin/roles', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  return data.map(role => {
    if (!isRecord(role) || !isRole(role.code) || typeof role.label !== 'string') throw new ApiError(UNEXPECTED, 502);
    return { code: role.code, label: role.label };
  });
}

export async function listAssignableOrganisations(token: string): Promise<AdminOrganisation[]> {
  const data = await send('/api/admin/organisations', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  return data.map(item => {
    const organisation = organisationOf(item);
    if (!organisation) throw new ApiError(UNEXPECTED, 502);
    return organisation;
  });
}

export async function listAssignmentAudits(token: string): Promise<AssignmentAudit[]> {
  const data = await send('/api/admin/assignment-audits', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  return data.map(entry => {
    if (!isRecord(entry) || typeof entry.id !== 'number' || typeof entry.recordedAt !== 'string'
      || !isRole(entry.previousRole) || !isRole(entry.assignedRole)) {
      throw new ApiError(UNEXPECTED, 502);
    }
    const organisation = organisationOf(entry.organisation);
    if (!organisation) throw new ApiError(UNEXPECTED, 502);
    return {
      id: entry.id,
      recordedAt: entry.recordedAt,
      operator: partyOf(entry.operator),
      target: partyOf(entry.target),
      previousRole: entry.previousRole,
      assignedRole: entry.assignedRole,
      previousOrganisation: organisationOf(entry.previousOrganisation),
      organisation,
    };
  });
}

export async function assignUser(token: string, userId: number, organisationId: number, role: Role): Promise<AdminUser> {
  const data = await send(`/api/admin/users/${userId}/assign`, token, {
    method: 'PATCH',
    body: JSON.stringify({ organisationId, role }),
  });
  if (!isRecord(data) || data.message !== 'User assigned successfully') throw new ApiError(UNEXPECTED, 502);
  return userOf(data.user);
}
