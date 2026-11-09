import { useRef, useState } from 'react';
import { ApiError } from '../signup/api';
import { createOrganisation, type AdminOrganisation } from './api';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';

export function CreateOrganisation({ token, onSessionEnded }: {
  token: string;
  onSessionEnded: () => void;
}) {
  const pending = useRef(false);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState('');
  const [retryable, setRetryable] = useState(false);
  const [created, setCreated] = useState<AdminOrganisation | null>(null);

  async function submit() {
    const nextCode = code.trim();
    const nextName = name.trim();
    if (pending.current || nextCode === '' || nextName === '') return;
    pending.current = true;
    setSubmitting(true);
    setProblem('');
    setRetryable(false);
    setCreated(null);
    try {
      const organisation = await createOrganisation(token, nextCode, nextName);
      setCreated(organisation);
      setCode('');
      setName('');
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      const unreachable = !(reason instanceof ApiError) || reason.status === 0;
      setRetryable(unreachable);
      setProblem(reason instanceof ApiError ? reason.message : UNREACHABLE);
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  }

  const empty = code.trim() === '' || name.trim() === '';
  return (
    <section className="audit-panel" aria-labelledby="add-organisation">
      <h3 id="add-organisation">Add an organisation</h3>
      <p className="intro">Add a company that an invitation can name and that another company can select when linking a shipment. This does not invite anyone, does not add a shipment, and does not change who can see an existing shipment. You still assign people only inside your own organisation.</p>
      <form className="admin-form" onSubmit={event => { event.preventDefault(); void submit(); }}>
        {created ? <div className="success-notice" role="status">{created.name} ({created.code}) was added.</div> : null}
        {problem ? <div className="error-notice" role="alert">{problem}{retryable ? <><br /><button type="button" className="retry-button" onClick={() => { void submit(); }}>Try again</button></> : null}</div> : null}
        <div className="field">
          <label htmlFor="organisation-code">Organisation code</label>
          <input id="organisation-code" value={code} autoComplete="off" spellCheck={false} disabled={submitting} onChange={event => setCode(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="organisation-name">Organisation name</label>
          <input id="organisation-name" value={name} autoComplete="off" disabled={submitting} onChange={event => setName(event.target.value)} />
        </div>
        <div className="form-actions">
          <button className="primary-button" type="submit" disabled={submitting || empty}>
            {submitting ? 'Adding organisation' : 'Add organisation'} <span aria-hidden="true">→</span>
          </button>
        </div>
      </form>
    </section>
  );
}
