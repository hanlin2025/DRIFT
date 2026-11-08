import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { passwordRules } from '../signup/validation';
import { createFirstAdministrator, loadSetupStatus, type SetupAccount, type SetupOrganisation } from './api';
import { validateSetup, type SetupFieldErrors, type SetupFields } from './validation';

const emptyFields: SetupFields = { organisationId: '', fullName: '', email: '', password: '', confirmation: '' };

export function SetupPage() {
  const [credential, setCredential] = useState('');
  const [organisations, setOrganisations] = useState<SetupOrganisation[] | null>(null);
  const [initialized, setInitialized] = useState(false);
  const [loading, setLoading] = useState(false);
  const [fields, setFields] = useState<SetupFields>(emptyFields);
  const [errors, setErrors] = useState<SetupFieldErrors>({});
  const [error, setError] = useState('');
  const [created, setCreated] = useState<SetupAccount | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  useEffect(() => { document.title = 'Set up the first administrator | DRIFT'; }, []);

  async function continueWithCredential(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    if (!credential.trim()) {
      setError('Enter the setup credential.');
      return;
    }
    busy.current = true;
    setLoading(true);
    setError('');
    setOrganisations(null);
    setInitialized(false);
    try {
      const status = await loadSetupStatus(credential);
      setInitialized(status.initialized);
      setOrganisations(status.organisations);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Setup could not be opened. Please try again.');
    } finally {
      busy.current = false;
      setLoading(false);
    }
  }

  function change(field: keyof SetupFields, value: string) {
    const next = { ...fields, [field]: value };
    setFields(next);
    if (errors[field]) setErrors(previous => ({ ...previous, [field]: validateSetup(next, organisations?.length ?? 0)[field] }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current || created || !organisations) return;
    const validation = validateSetup(fields, organisations.length);
    setErrors(validation);
    setError('');
    if (Object.keys(validation).length) {
      const first = event.currentTarget.elements.namedItem(Object.keys(validation)[0]);
      if (first instanceof HTMLElement) first.focus();
      return;
    }
    busy.current = true;
    setSubmitting(true);
    try {
      const account = await createFirstAdministrator(credential, {
        organisationId: Number(fields.organisationId),
        fullName: fields.fullName.trim(),
        email: fields.email.trim(),
        password: fields.password,
      });
      setFields(emptyFields);
      setCreated(account);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409 && reason.message.includes('already has an administrator')) {
        setInitialized(true);
      }
      if (reason instanceof ApiError) {
        const mapped: SetupFieldErrors = {};
        for (const name of ['organisationId', 'fullName', 'email', 'password'] as const) {
          if (reason.fields[name]) mapped[name] = reason.fields[name];
        }
        setErrors(mapped);
      }
      setError(reason instanceof Error ? reason.message : 'The administrator could not be created. Please try again.');
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  return (
    <>
      <p className="eyebrow form-eyebrow">FIRST ADMINISTRATOR</p>
      <h2>Set up this installation.</h2>
      <p className="intro">An operator creates the one initial administrator. Colleagues still join by invitation.</p>
      {error && <div className="error-notice" role="alert">{error}</div>}
      {created ? (
        <div className="success-notice" role="status">
          <strong>{created.fullName} is the administrator for {created.organisation.name}.</strong>
          <br />Sign in with {created.email}. The setup credential is not part of the account.
        </div>
      ) : null}
      {organisations === null && !created ? (
        <form onSubmit={continueWithCredential} className="signup-form" noValidate aria-busy={loading}>
          <div className="field">
            <label htmlFor="setup-credential">Setup credential</label>
            <input id="setup-credential" name="credential" type="password" autoComplete="off" spellCheck={false} value={credential} onChange={event => setCredential(event.target.value)} disabled={loading} />
            <p className="field-hint">This is the one-time operator credential for this deployment. It is not stored in the browser.</p>
          </div>
          <button type="submit" className="primary-button" disabled={loading}>{loading ? 'Checking setup...' : 'Continue'} <span aria-hidden="true">&#8594;</span></button>
        </form>
      ) : null}
      {initialized && !created ? <div className="notice" role="status">This installation already has an administrator. <Link to="/login">Log in</Link></div> : null}
      {organisations && !initialized && !created && organisations.length === 0 ? (
        <div className="notice" role="status">No eligible organisation is available. Setup cannot create an administrator until an active organisation exists.</div>
      ) : null}
      {organisations && !initialized && !created && organisations.length > 0 ? (
        <form onSubmit={submit} className="signup-form" noValidate aria-busy={submitting}>
          <div className="field">
            <label htmlFor="setup-organisation">Organisation</label>
            <select id="setup-organisation" name="organisationId" value={fields.organisationId} onChange={event => change('organisationId', event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.organisationId)} aria-describedby={errors.organisationId ? 'setup-organisation-error' : undefined}>
              <option value="">Select an organisation</option>
              {organisations.map(organisation => <option key={organisation.id} value={organisation.id}>{organisation.name}</option>)}
            </select>
            {errors.organisationId && <p className="field-error" id="setup-organisation-error">{errors.organisationId}</p>}
          </div>
          <div className="field">
            <label htmlFor="setup-name">Full name</label>
            <input id="setup-name" name="fullName" autoComplete="name" value={fields.fullName} onChange={event => change('fullName', event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.fullName)} aria-describedby={errors.fullName ? 'setup-name-error' : undefined} />
            {errors.fullName && <p className="field-error" id="setup-name-error">{errors.fullName}</p>}
          </div>
          <div className="field">
            <label htmlFor="setup-email">Work email</label>
            <input id="setup-email" name="email" type="email" autoComplete="email" value={fields.email} onChange={event => change('email', event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.email)} aria-describedby={errors.email ? 'setup-email-error' : undefined} />
            {errors.email && <p className="field-error" id="setup-email-error">{errors.email}</p>}
          </div>
          <div className="field">
            <label htmlFor="setup-password">Password</label>
            <div className="password-input">
              <input id="setup-password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={fields.password} onChange={event => change('password', event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.password)} aria-describedby={errors.password ? 'setup-password-error password-rules' : 'password-rules'} />
              <button type="button" className="visibility-button" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Hide' : 'Show'}</button>
            </div>
            {errors.password && <p className="field-error" id="setup-password-error">{errors.password}</p>}
            <ul id="password-rules" className="password-rules" aria-label="Password requirements">{passwordRules(fields.password).map(rule => <li key={rule.label} data-met={rule.met}><span className="sr-only">{rule.met ? 'Met: ' : 'Required: '}</span>{rule.label}</li>)}</ul>
          </div>
          <div className="field">
            <label htmlFor="setup-confirmation">Confirm password</label>
            <input id="setup-confirmation" name="confirmation" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={fields.confirmation} onChange={event => change('confirmation', event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.confirmation)} aria-describedby={errors.confirmation ? 'setup-confirmation-error' : undefined} />
            {errors.confirmation && <p className="field-error" id="setup-confirmation-error">{errors.confirmation}</p>}
          </div>
          <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Creating administrator...' : 'Create administrator'} <span aria-hidden="true">&#8594;</span></button>
        </form>
      ) : null}
      <p className="login-prompt">{created ? <Link to="/login">Log in</Link> : <>Already an administrator? <Link to="/login">Log in</Link></>}</p>
    </>
  );
}
