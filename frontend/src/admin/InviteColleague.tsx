import { useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../signup/api';
import { inviteColleague, type AdminOrganisation, type AdminRole } from './api';
import type { Role } from '../session/session';

const empty = { organisationId: '', role: '' as Role | '', email: '' };

export function InviteColleague({ token, organisations, roles, onSessionEnded }: {
  token: string;
  organisations: AdminOrganisation[];
  roles: AdminRole[];
  onSessionEnded: () => void;
}) {
  const invitable = roles.filter(role => role.code !== 'ADMIN');
  const [fields, setFields] = useState(empty);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [pending, setPending] = useState(false);
  const busy = useRef(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    if (!fields.email.trim() || !fields.role || !fields.organisationId) {
      setSaved('');
      setError('Enter an email, organisation and role.');
      return;
    }
    busy.current = true;
    setPending(true);
    setError('');
    setSaved('');
    try {
      const issued = await inviteColleague(token, Number(fields.organisationId), fields.role, fields.email.trim());
      setFields(empty);
      setSaved(`Invitation sent to ${issued.email}. It expires ${issued.expiresAt}.`);
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setError(reason instanceof Error ? reason.message : 'The invitation could not be sent.');
    } finally {
      busy.current = false;
      setPending(false);
    }
  }

  return (
    <section className="audit-panel" aria-labelledby="invite-colleague">
      <h3 id="invite-colleague">Invite a colleague</h3>
      <p className="intro">Send an email invitation for a role in your organisation. The invitation chooses the email, role and organisation. It is not shown here again.</p>
      {organisations.length === 0 ? <p className="notice" role="status">No eligible organisation is available.</p> : (
        <form className="admin-form" onSubmit={event => { void submit(event); }} noValidate aria-busy={pending}>
          {saved ? <div className="success-notice" role="status">{saved}</div> : null}
          {error ? <div className="error-notice" role="alert">{error}</div> : null}
          <div className="field">
            <label htmlFor="invite-email">Colleague email</label>
            <input id="invite-email" type="email" autoComplete="off" value={fields.email} onChange={event => setFields(current => ({ ...current, email: event.target.value }))} disabled={pending} />
          </div>
          <div className="field">
            <label htmlFor="invite-organisation">Invitation organisation</label>
            <select id="invite-organisation" value={fields.organisationId} onChange={event => setFields(current => ({ ...current, organisationId: event.target.value }))} disabled={pending}>
              <option value="">Select an organisation</option>
              {organisations.map(organisation => <option key={organisation.id} value={organisation.id}>{organisation.name}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="invite-role">Invitation role</label>
            <select id="invite-role" value={fields.role} onChange={event => setFields(current => ({ ...current, role: event.target.value as Role }))} disabled={pending}>
              <option value="">Select a role</option>
              {invitable.map(role => <option key={role.code} value={role.code}>{role.label}</option>)}
            </select>
          </div>
          <button className="primary-button" type="submit" disabled={pending}>{pending ? 'Sending invitation...' : 'Send invitation'}</button>
        </form>
      )}
    </section>
  );
}
