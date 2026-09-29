import type { Role } from '../signup/api';

const KEY = 'drift.session';
const ENDED = 'drift.session.ended';

export type Company = { id: number; code: string; name: string };
export type Session = {
  token: string;
  expiresAt: string;
  id: number;
  fullName: string;
  email: string;
  role: Role;
  company: Company | null;
};

export function readSession(): Session | null {
  const raw = sessionStorage.getItem(KEY);
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null || !('token' in value) || typeof value.token !== 'string') return null;
    return value as Session;
  } catch {
    return null;
  }
}

export function writeSession(session: Session) {
  sessionStorage.setItem(KEY, JSON.stringify(session));
}

export function clearSession() {
  sessionStorage.removeItem(KEY);
  sessionStorage.removeItem(ENDED);
}

export function endSession() {
  clearSession();
  sessionStorage.setItem(ENDED, '1');
}

export function sessionHasEnded() {
  return sessionStorage.getItem(ENDED) === '1';
}

export function isExpired(expiresAt: string, now = Date.now()) {
  const time = Date.parse(expiresAt);
  return Number.isNaN(time) || time <= now;
}

export function homePath(role: Role) {
  return role === 'IMPORTER' ? '/importer' : '/freight-forwarder';
}
