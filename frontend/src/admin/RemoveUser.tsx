import { useEffect, useId, useRef, useState, type KeyboardEvent, type MouseEvent, type SyntheticEvent } from 'react';
import { createPortal } from 'react-dom';
import { ApiError } from '../signup/api';
import { removeUser, type AdminUser } from './api';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';

export function RemoveUser({ token, user, onSessionEnded, onRemoved, onFailed }: {
  token: string;
  user: AdminUser;
  onSessionEnded: () => void;
  onRemoved: (user: AdminUser) => void;
  onFailed: (error: ApiError) => void;
}) {
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLButtonElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const pending = useRef(false);
  const [opened, setOpened] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  function open() {
    if (pending.current || opened) return;
    setOpened(true);
  }

  function dismiss() {
    if (pending.current) return;
    dialogRef.current?.close();
    setOpened(false);
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
    try {
      const updated = await removeUser(token, user.id);
      pending.current = false;
      setSubmitting(false);
      dismiss();
      onRemoved(updated);
    } catch (reason) {
      pending.current = false;
      setSubmitting(false);
      dismiss();
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      onFailed(reason instanceof ApiError ? reason : new ApiError(UNREACHABLE, 0));
    }
  }

  return (
    <>
      <button ref={openerRef} type="button" className="secondary-button" aria-label={`Remove ${user.fullName}`} onClick={open}>Remove user</button>
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
          <h2 id={titleId}>Remove user</h2>
          <p id={descriptionId}>Removing {user.fullName} ({user.email}) keeps their account record but stops them from using DRIFT. Their current session will end.</p>
          <div className="remove-dialog-actions">
            <button ref={cancelRef} type="button" className="quiet-button" onClick={dismiss} disabled={submitting}>Cancel</button>
            <button type="button" className="primary-button" onClick={confirm} disabled={submitting}>{submitting ? 'Removing...' : 'Remove'}</button>
          </div>
        </dialog>,
        document.body,
      )}
    </>
  );
}
