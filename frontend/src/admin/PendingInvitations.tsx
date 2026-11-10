import { useEffect, useId, useRef, useState, type KeyboardEvent, type MouseEvent, type SyntheticEvent } from 'react';
import { createPortal } from 'react-dom';
import { ApiError } from '../signup/api';
import { listPendingInvitations, withdrawInvitation, type AdminRole, type ManagedInvitation } from './api';

const RETRYABLE = 'The invitation could not be withdrawn. It is still pending. Try again.';

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
  const [saved, setSaved] = useState('');
  const [actionError, setActionError] = useState('');
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
      <p className="intro">Invitations for your organisation that have not been used. Withdraw one only after confirming. One that is past its expiry is marked expired and cannot be made valid.</p>
      {saved ? <div className="success-notice" role="status">{saved}</div> : null}
      {actionError ? <div className="error-notice" role="alert">{actionError}</div> : null}
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
              {invitation.expired ? null : (
                <WithdrawInvitation
                  token={token}
                  invitation={invitation}
                  onSessionEnded={onSessionEnded}
                  onWithdrawn={() => {
                    setActionError('');
                    setSaved(`Invitation for ${invitation.email} withdrawn.`);
                    setAttempt(value => value + 1);
                  }}
                  onRejected={error => {
                    setSaved('');
                    setActionError(error.message);
                    setAttempt(value => value + 1);
                  }}
                />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function retryable(reason: unknown) {
  return reason instanceof ApiError && (reason.status === 0 || reason.status >= 500);
}

function WithdrawInvitation({ token, invitation, onSessionEnded, onWithdrawn, onRejected }: {
  token: string;
  invitation: ManagedInvitation;
  onSessionEnded: () => void;
  onWithdrawn: () => void;
  onRejected: (error: ApiError) => void;
}) {
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLButtonElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const pending = useRef(false);
  const [opened, setOpened] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState('');

  function open() {
    if (pending.current || opened) return;
    setFailure('');
    setOpened(true);
  }

  function dismiss() {
    if (pending.current) return;
    dialogRef.current?.close();
    setOpened(false);
    setFailure('');
    openerRef.current?.focus();
  }

  useEffect(() => {
    if (!opened) return;
    const dialog = dialogRef.current;
    if (!dialog || dialog.open) return;
    dialog.showModal();
    cancelRef.current?.focus();
  }, [opened]);

  function onKeyDown(event: KeyboardEvent<HTMLDialogElement>) {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    dismiss();
  }

  function onCancel(event: SyntheticEvent<HTMLDialogElement>) {
    if (pending.current) event.preventDefault();
  }

  function onBackdrop(event: MouseEvent<HTMLDialogElement>) {
    if (event.target !== event.currentTarget) return;
    dismiss();
  }

  async function confirm() {
    if (pending.current) return;
    pending.current = true;
    setSubmitting(true);
    setFailure('');
    try {
      await withdrawInvitation(token, invitation.id);
      pending.current = false;
      setSubmitting(false);
      dismiss();
      onWithdrawn();
    } catch (reason) {
      pending.current = false;
      setSubmitting(false);
      if (reason instanceof ApiError && reason.status === 401) {
        dismiss();
        onSessionEnded();
        return;
      }
      if (retryable(reason)) {
        setFailure(RETRYABLE);
        return;
      }
      dismiss();
      onRejected(reason instanceof ApiError ? reason : new ApiError(RETRYABLE, 0));
    }
  }

  return (
    <>
      <button ref={openerRef} type="button" className="secondary-button" aria-label={`Withdraw invitation for ${invitation.email}`} onClick={open}>
        Withdraw invitation
      </button>
      {opened && createPortal(
        <dialog
          ref={dialogRef}
          className="remove-dialog"
          aria-labelledby={titleId}
          aria-describedby={descriptionId}
          onKeyDown={onKeyDown}
          onCancel={onCancel}
          onClick={onBackdrop}
        >
          <h2 id={titleId}>Withdraw invitation</h2>
          <p id={descriptionId}>Withdraw the invitation for {invitation.email}? They will not be able to create an account with it.</p>
          {failure ? <div className="error-notice" role="alert">{failure}</div> : null}
          <div className="remove-dialog-actions">
            <button ref={cancelRef} type="button" className="quiet-button" onClick={dismiss} disabled={submitting}>Cancel</button>
            <button type="button" className="primary-button" onClick={() => { void confirm(); }} disabled={submitting}>
              {submitting ? 'Withdrawing...' : failure ? 'Try again' : 'Withdraw'}
            </button>
          </div>
        </dialog>,
        document.body,
      )}
    </>
  );
}
