import { ApiError } from '../signup/api';
import { type Role } from '../session/session';

export type AdminOrganisation = { id: number; code: string; name: string };
export type AdminRole = { code: Role; label: string };
export type AccountStatus = 'ACTIVE' | 'REMOVED' | 'PENDING_VERIFICATION';
export type AdminUser = {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  organisation: AdminOrganisation | null;
  status: AccountStatus;
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
export type AccountStatusAudit = {
  id: number;
  recordedAt: string;
  operator: AssignmentAuditParty;
  target: AssignmentAuditParty;
  organisationId: number;
  previousStatus: AccountStatus;
  newStatus: AccountStatus;
};

const UNEXPECTED = 'DRIFT returned an unexpected response. Please try again shortly.';
const ROLES: Role[] = ['IMPORTER', 'FREIGHT_FORWARDER', 'ADMIN', 'LOGISTICS_MANAGER'];
const STATUSES: AccountStatus[] = ['ACTIVE', 'REMOVED', 'PENDING_VERIFICATION'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isRole(value: unknown): value is Role {
  return typeof value === 'string' && ROLES.includes(value as Role);
}

function isStatus(value: unknown): value is AccountStatus {
  return typeof value === 'string' && STATUSES.includes(value as AccountStatus);
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
    || typeof value.email !== 'string' || !isRole(value.role) || !('organisation' in value) || !isStatus(value.status)) {
    throw new ApiError(UNEXPECTED, 502);
  }
  return {
    id: value.id,
    fullName: value.fullName,
    email: value.email,
    role: value.role,
    organisation: organisationOf(value.organisation),
    status: value.status,
  };
}

async function send(path: string, token: string, init: RequestInit = {}): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      credentials: token ? init.credentials : 'include',
      headers: {
        ...init.headers,
        ...(token ? { Authorization: `Bearer ${token}` } : { 'X-DRIFT-Request': '1' }),
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
    const fields: Record<string, string> = {};
    if (isRecord(data) && isRecord(data.errors)) {
      for (const [key, value] of Object.entries(data.errors)) if (typeof value === 'string') fields[key] = value;
    }
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status, fields);
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

export async function createOrganisation(token: string, code: string, name: string): Promise<AdminOrganisation> {
  const data = await send('/api/admin/organisations', token, {
    method: 'POST',
    body: JSON.stringify({ code, name }),
  });
  const organisation = organisationOf(data);
  if (!organisation) throw new ApiError(UNEXPECTED, 502);
  return organisation;
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

export async function listInvitationOrganisations(token: string): Promise<AdminOrganisation[]> {
  const data = await send('/api/admin/invitation-organisations', token);
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

export async function removeUser(token: string, userId: number): Promise<AdminUser> {
  const data = await send(`/api/admin/users/${userId}/remove`, token, { method: 'PATCH' });
  return userOf(data);
}

export async function reactivateUser(token: string, userId: number): Promise<AdminUser> {
  const data = await send(`/api/admin/users/${userId}/reactivate`, token, { method: 'PATCH' });
  return userOf(data);
}

export async function listAccountStatusAudits(token: string): Promise<AccountStatusAudit[]> {
  const data = await send('/api/admin/account-status-audits', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  return data.map(entry => {
    if (!isRecord(entry) || typeof entry.id !== 'number' || typeof entry.recordedAt !== 'string'
      || typeof entry.organisationId !== 'number' || !isStatus(entry.previousStatus) || !isStatus(entry.newStatus)) {
      throw new ApiError(UNEXPECTED, 502);
    }
    return {
      id: entry.id,
      recordedAt: entry.recordedAt,
      operator: partyOf(entry.operator),
      target: partyOf(entry.target),
      organisationId: entry.organisationId,
      previousStatus: entry.previousStatus,
      newStatus: entry.newStatus,
    };
  });
}

export type ManagedInvitation = {
  id: number;
  email: string;
  role: Role;
  organisation: AdminOrganisation;
  expiresAt: string;
  expired: boolean;
};
export type IssuedInvitation = { email: string; role: Role; organisation: AdminOrganisation; expiresAt: string };

const INVITATION_SECRET_KEYS = ['token', 'code', 'tokenHash', 'password', 'invitationToken'];

export async function listPendingInvitations(token: string): Promise<ManagedInvitation[]> {
  const data = await send('/api/admin/invitations', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  return data.map(item => {
    if (!isRecord(item) || typeof item.id !== 'number' || typeof item.email !== 'string' || !isRole(item.role)
      || typeof item.expiresAt !== 'string' || typeof item.expired !== 'boolean'
      || INVITATION_SECRET_KEYS.some(key => key in item)) {
      throw new ApiError(UNEXPECTED, 502);
    }
    const organisation = organisationOf(item.organisation);
    if (!organisation) throw new ApiError(UNEXPECTED, 502);
    return {
      id: item.id,
      email: item.email,
      role: item.role,
      organisation,
      expiresAt: item.expiresAt,
      expired: item.expired,
    };
  });
}

export async function withdrawInvitation(token: string, invitationId: number): Promise<void> {
  const data = await send(`/api/admin/invitations/${invitationId}/withdraw`, token, { method: 'POST' });
  if (!isRecord(data) || data.status !== 'REVOKED' || typeof data.id !== 'number' || data.id !== invitationId
    || INVITATION_SECRET_KEYS.some(key => key in data)) {
    throw new ApiError(UNEXPECTED, 502);
  }
}

export async function inviteColleague(token: string, organisationId: number, role: Role, email: string): Promise<IssuedInvitation> {
  const data = await send('/api/admin/invitations', token, {
    method: 'POST',
    body: JSON.stringify({ organisationId, role, email }),
  });
  if (!isRecord(data) || typeof data.email !== 'string' || !isRole(data.role) || typeof data.expiresAt !== 'string'
    || 'token' in data || 'code' in data || 'password' in data) {
    throw new ApiError(UNEXPECTED, 502);
  }
  const organisation = organisationOf(data.organisation);
  if (!organisation) throw new ApiError(UNEXPECTED, 502);
  return { email: data.email, role: data.role, organisation, expiresAt: data.expiresAt };
}
