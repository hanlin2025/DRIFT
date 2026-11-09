import { useEffect, useState } from 'react';
import { ApiError } from '../signup/api';
import { listPendingInvitations, type AdminRole, type ManagedInvitation } from './api';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';

function formatExpiry(value: string) {
  const time = Date.parse(value);
  if (Number.isNaN(time)) return value;
  return new Intl.DateTimeFormat('en-SG', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Singapore' }).format(time);
}

export function PendingInvitations({ token, roles, onSessionEnded }: {
  token: string;
  roles: AdminRole[];
  onSessionEnded: () => void;
}) {
  const [invitations, setInvitations] = useState<ManagedInvitation[] | null>(null);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);
  const label = (invitation: ManagedInvitation) => roles.find(role => role.code === invitation.role)?.label ?? invitation.role;

  useEffect(() => {
    let active = true;
    setProblem('');
    listPendingInvitations(token).then(found => {
      if (!active) return;
      setInvitations(found);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setInvitations(null);
      setProblem(reason instanceof Error ? reason.message : UNREACHABLE);
    });
    return () => { active = false; };
  }, [token, attempt, onSessionEnded]);

  return (
    <section className="audit-panel" aria-labelledby="pending-invitations">
      <h3 id="pending-invitations">Pending invitations</h3>
      <p className="intro">Invitations for your organisation that have not been used. One that is past its expiry is marked expired.</p>
      {problem ? (
        <>
          <div className="error-notice" role="alert">{problem}</div>
          <button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button>
        </>
      ) : invitations === null ? <p className="notice" role="status">Loading pending invitations...</p> : invitations.length === 0 ? (
        <p className="notice" role="status">No pending invitations.</p>
      ) : (
        <ul className="audit-list">
          {invitations.map(invitation => (
            <li key={invitation.id}>
              <strong>{invitation.email}</strong>
              <span>{label(invitation)} at {invitation.organisation.name}</span>
              <span>{invitation.expired ? 'Expired' : 'Pending'}</span>
              <time dateTime={invitation.expiresAt}>Expires {formatExpiry(invitation.expiresAt)}</time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
