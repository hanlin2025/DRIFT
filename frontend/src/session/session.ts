const KEY = 'drift.session';
const ENDED = 'drift.session.ended';
const PROFILE = 'drift.profile';

export type Role = 'IMPORTER' | 'FREIGHT_FORWARDER' | 'ADMIN' | 'LOGISTICS_MANAGER';
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

function parse(raw: string | null): Session | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null || !('token' in value) || typeof value.token !== 'string') return null;
    return value as Session;
  } catch {
    return null;
  }
}

export function readSession(): Session | null {
  return parse(sessionStorage.getItem(KEY)) ?? parse(localStorage.getItem(PROFILE));
}

export function writeSession(session: Session) {
  sessionStorage.setItem(KEY, JSON.stringify(session));
  if (!session.token) localStorage.setItem(PROFILE, JSON.stringify(session));
}

export function clearSession() {
  sessionStorage.removeItem(KEY);
  sessionStorage.removeItem(ENDED);
  localStorage.removeItem(PROFILE);
}

export async function signOut() {
  const current = readSession();
  const headers = new Headers({ 'X-DRIFT-Request': '1' });
  if (current?.token) headers.set('Authorization', `Bearer ${current.token}`);
  try {
    await fetch('/api/logout', {
      method: 'POST',
      credentials: 'include',
      headers,
      signal: AbortSignal.timeout(15000),
    });
  } catch {
    // The browser copy is removed even when the server cannot be reached.
  }
  clearSession();
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
  if (role === 'IMPORTER') return '/importer';
  if (role === 'ADMIN') return '/admin';
  if (role === 'LOGISTICS_MANAGER') return '/logistics-manager';
  return '/freight-forwarder';
}

export function shipmentListPath(role: Role) {
  if (role === 'ADMIN') return '/admin/shipments';
  return homePath(role);
}

export function shipmentDetailPath(role: Role, shipmentId: string) {
  if (role === 'ADMIN') return `/admin/shipments/${shipmentId}`;
  return `${homePath(role)}/shipments/${shipmentId}`;
}
