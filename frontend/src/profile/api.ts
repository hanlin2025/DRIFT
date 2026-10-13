import { ApiError } from '../signup/api';
import { type Role } from '../session/session';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export const RISK_LEVELS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export type ProfileCompany = { id: number; code: string; name: string };

export type Profile = {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  company: ProfileCompany | null;
  riskEmailNotificationsEnabled: boolean;
  minimumRiskNotificationLevel: RiskLevel;
};

export type ProfileUpdate = {
  fullName: string;
  riskEmailNotificationsEnabled: boolean;
  minimumRiskNotificationLevel: RiskLevel;
  currentPassword?: string;
  newPassword?: string;
};

const UNEXPECTED = 'DRIFT returned an unexpected response. Please try again shortly.';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isRole(value: unknown): value is Role {
  return value === 'IMPORTER' || value === 'FREIGHT_FORWARDER' || value === 'ADMIN' || value === 'LOGISTICS_MANAGER';
}

function isRiskLevel(value: unknown): value is RiskLevel {
  return value === 'LOW' || value === 'MEDIUM' || value === 'HIGH' || value === 'CRITICAL';
}

export function roleLabel(role: Role) {
  switch (role) {
    case 'IMPORTER': return 'Importer';
    case 'FREIGHT_FORWARDER': return 'Freight forwarder';
    case 'ADMIN': return 'Administrator';
    case 'LOGISTICS_MANAGER': return 'Logistics manager';
  }
}

export function profileUpdateBody(update: ProfileUpdate): Record<string, unknown> {
  const body: Record<string, unknown> = {
    fullName: update.fullName,
    riskEmailNotificationsEnabled: update.riskEmailNotificationsEnabled,
    minimumRiskNotificationLevel: update.minimumRiskNotificationLevel,
  };
  const currentPassword = update.currentPassword ?? '';
  const newPassword = update.newPassword ?? '';
  if (currentPassword !== '' || newPassword !== '') {
    body.currentPassword = currentPassword;
    body.newPassword = newPassword;
  }
  return body;
}

function profileOf(data: unknown): Profile {
  if (!isRecord(data) || typeof data.id !== 'number' || typeof data.fullName !== 'string'
    || typeof data.email !== 'string' || !isRole(data.role)
    || typeof data.riskEmailNotificationsEnabled !== 'boolean'
    || !isRiskLevel(data.minimumRiskNotificationLevel) || !('company' in data)) {
    throw new ApiError(UNEXPECTED, 502);
  }
  if (data.company === null) {
    return {
      id: data.id, fullName: data.fullName, email: data.email, role: data.role, company: null,
      riskEmailNotificationsEnabled: data.riskEmailNotificationsEnabled,
      minimumRiskNotificationLevel: data.minimumRiskNotificationLevel,
    };
  }
  if (!isRecord(data.company) || typeof data.company.id !== 'number'
    || typeof data.company.code !== 'string' || typeof data.company.name !== 'string') {
    throw new ApiError(UNEXPECTED, 502);
  }
  return {
    id: data.id, fullName: data.fullName, email: data.email, role: data.role,
    company: { id: data.company.id, code: data.company.code, name: data.company.name },
    riskEmailNotificationsEnabled: data.riskEmailNotificationsEnabled,
    minimumRiskNotificationLevel: data.minimumRiskNotificationLevel,
  };
}

async function send(path: string, token: string, init: RequestInit = {}): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { ...init.headers, Authorization: `Bearer ${token}` },
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
    throw new ApiError(UNEXPECTED, response.status);
  }
  const data: unknown = await response.json();
  if (!response.ok) {
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status);
  }
  return data;
}

export async function getProfile(token: string): Promise<Profile> {
  return profileOf(await send('/api/profile', token));
}

export async function updateProfile(token: string, update: ProfileUpdate): Promise<Profile> {
  return profileOf(await send('/api/profile', token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profileUpdateBody(update)),
  }));
}
