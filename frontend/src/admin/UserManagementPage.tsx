import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { loadSession } from '../login/api';
import { clearSession, endSession, homePath, isExpired, readSession, sessionHasEnded, writeSession, type Role, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { assignUser, listAssignableOrganisations, listAssignableRoles, listAssignableUsers, listAssignmentAudits, type AdminOrganisation, type AdminRole, type AdminUser, type AssignmentAudit } from './api';

function Wordmark() {
  return (
    <a className="wordmark" href="/admin" aria-label="DRIFT home">
      <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
      DRIFT<span className="wordmark-dot">.</span>
    </a>
  );
}

export function UserManagementPage({ account, onSignOut, onSessionEnded }: {
  account: Session;
  onSignOut: () => void;
  onSessionEnded: () => void;
}) {
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [roles, setRoles] = useState<AdminRole[]>([]);
  const [organisations, setOrganisations] = useState<AdminOrganisation[]>([]);
  const [audits, setAudits] = useState<AssignmentAudit[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [organisationId, setOrganisationId] = useState('');
  const [role, setRole] = useState<Role | ''>('');
  const [problem, setProblem] = useState('');
  const [saved, setSaved] = useState('');
  const [pending, setPending] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const selected = users?.find(user => user.id === selectedId) ?? null;

  useEffect(() => {
    let active = true;
    setProblem('');
    setUsers(null);
    Promise.all([
      listAssignableUsers(account.token),
      listAssignableRoles(account.token),
      listAssignableOrganisations(account.token),
      listAssignmentAudits(account.token),
    ]).then(([foundUsers, foundRoles, foundOrganisations, foundAudits]) => {
      if (!active) return;
      setUsers(foundUsers);
      setRoles(foundRoles);
      setOrganisations(foundOrganisations);
      setAudits(foundAudits);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    });
    return () => { active = false; };
  }, [account.token, attempt, onSessionEnded]);

  function choose(user: AdminUser) {
    setSelectedId(user.id);
    setSaved('');
    setProblem('');
    setRole(user.role);
    const organisationStillActive = user.organisation != null && organisations.some(item => item.id === user.organisation?.id);
    setOrganisationId(organisationStillActive ? String(user.organisation?.id) : '');
  }

  async function save() {
    if (!selected || !role || organisationId === '') return;
    setPending(true);
    setProblem('');
    setSaved('');
    try {
      const updated = await assignUser(account.token, selected.id, Number(organisationId), role);
      const changed = updated.role !== selected.role || updated.organisation?.id !== selected.organisation?.id;
      if (updated.id === account.id && changed) {
        onSessionEnded();
        return;
      }
      setUsers(current => current?.map(user => user.id === updated.id ? updated : user) ?? null);
      setSaved('User assigned successfully');
      setAudits(await listAssignmentAudits(account.token));
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="workspace">
      <header className="workspace-bar">
        <Wordmark />
        <Link className="workspace-nav" to="/admin" aria-current="page">User management</Link>
        <button type="button" className="sign-out" onClick={onSignOut}>Sign out</button>
      </header>
      <main className="workspace-main">
        <p className="eyebrow">ADMINISTRATOR</p>
        <h2>User management</h2>
        <p className="intro">Assign each person to an organisation and a role. Changing either one ends their current session.</p>
        {problem && users === null ? <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></div> : null}
        {users === null && !problem ? <div className="notice" role="status">Loading users...</div> : null}
        {users !== null ? (
          <form className="admin-form admin-layout" onSubmit={event => { event.preventDefault(); void save(); }}>
            {saved ? <div className="success-notice" role="status">{saved}</div> : null}
            {problem ? <div className="error-notice" role="alert">{problem}</div> : null}
            <fieldset className="user-choices">
              <legend>User</legend>
              {users.length === 0 ? <p className="notice">No users to assign.</p> : users.map(user => (
                <label className="user-choice" key={user.id}>
                  <input type="radio" name="assignee" checked={selectedId === user.id} onChange={() => choose(user)} />
                  <strong>{user.fullName}</strong>
                  <span>{user.email} · {roles.find(item => item.code === user.role)?.label ?? user.role} · {user.organisation?.name ?? 'No organisation'}</span>
                </label>
              ))}
            </fieldset>
            <div className="field">
              <label htmlFor="assignment-organisation">Organisation</label>
              <select id="assignment-organisation" value={organisationId} onChange={event => setOrganisationId(event.target.value)} disabled={selected == null || pending}>
                <option value="">Select an organisation</option>
                {organisations.map(organisation => <option key={organisation.id} value={organisation.id}>{organisation.name}</option>)}
              </select>
              {selected?.organisation && organisationId === '' ? <p className="field-hint">The current organisation is inactive. Choose an active organisation.</p> : null}
            </div>
            <div className="field">
              <label htmlFor="assignment-role">Role</label>
              <select id="assignment-role" value={role} onChange={event => setRole(event.target.value as Role)} disabled={selected == null || pending}>
                <option value="">Select a role</option>
                {roles.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
              </select>
            </div>
            <button className="primary-button" type="submit" disabled={pending || selected == null || role === '' || organisationId === ''}>
              {pending ? 'Saving assignment' : 'Save assignment'} <span aria-hidden="true">→</span>
            </button>
          </form>
        ) : null}
        {users !== null ? <AssignmentHistory audits={audits} roles={roles} /> : null}
      </main>
    </div>
  );
}

function AssignmentHistory({ audits, roles }: { audits: AssignmentAudit[]; roles: AdminRole[] }) {
  const label = (code: Role) => roles.find(item => item.code === code)?.label ?? code;
  const place = (organisation: AdminOrganisation | null) => organisation?.name ?? 'No organisation';
  return (
    <section className="audit-panel" aria-labelledby="assignment-history">
      <h3 id="assignment-history">Assignment history</h3>
      {audits.length === 0 ? <p className="notice">No assignment changes yet.</p> : (
        <ol className="audit-list">
          {audits.map(audit => (
            <li key={audit.id}>
              <strong>{audit.operator.fullName}</strong> assigned <strong>{audit.target.fullName}</strong>
              <span>{label(audit.previousRole)} at {place(audit.previousOrganisation)} → {label(audit.assignedRole)} at {place(audit.organisation)}</span>
              <time dateTime={audit.recordedAt}>{formatRecordedAt(audit.recordedAt)}</time>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function formatRecordedAt(value: string) {
  const time = Date.parse(value);
  if (Number.isNaN(time)) return value;
  return new Intl.DateTimeFormat('en-SG', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Singapore' }).format(time);
}

const MAX_TIMER_DELAY = 2_147_483_647;

export function AdminRoute() {
  const navigate = useNavigate();
  const [account, setAccount] = useState<Session | null>(null);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);
  const sessionEnded = useCallback(() => {
    endSession();
    navigate('/login', { replace: true, state: { expired: true } });
  }, [navigate]);

  useEffect(() => {
    const saved = readSession();
    if (!saved || isExpired(saved.expiresAt)) {
      const expired = Boolean(saved) || sessionHasEnded();
      if (saved) endSession();
      navigate('/login', { replace: true, state: expired ? { expired: true } : undefined });
      return;
    }
    let active = true;
    setProblem('');
    loadSession(saved.token).then(current => {
      if (!active) return;
      const next = { ...current, token: saved.token };
      writeSession(next);
      if (next.role !== 'ADMIN') {
        navigate(homePath(next.role), { replace: true });
        return;
      }
      setAccount(next);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        sessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    });
    return () => { active = false; };
  }, [navigate, attempt, sessionEnded]);

  useEffect(() => {
    if (!account) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const check = () => {
      if (isExpired(account.expiresAt)) {
        endSession();
        navigate('/login', { replace: true, state: { expired: true } });
        return;
      }
      timer = setTimeout(check, Math.min(Date.parse(account.expiresAt) - Date.now(), MAX_TIMER_DELAY));
    };
    check();
    return () => clearTimeout(timer);
  }, [account, navigate]);

  if (!account) return <div className="workspace"><main className="workspace-main">{problem
    ? <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></div>
    : <div className="notice" role="status">Checking your session...</div>}</main></div>;

  return <UserManagementPage account={account} onSignOut={() => { clearSession(); navigate('/login', { replace: true }); }} onSessionEnded={sessionEnded} />;
}
