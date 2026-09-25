import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, registerAccount, resolveInvitation, type Invitation } from './api';
import { passwordRules, validateSignup, type FieldErrors, type SignupFields } from './validation';

const roleNames = { IMPORTER: 'Importer', FREIGHT_FORWARDER: 'Freight forwarder' };
const emptyFields: SignupFields = { fullName: '', password: '', confirmation: '' };

export function SignupPage() {
  const [token, setToken] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('invite') ?? '');
  const [code, setCode] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [invitation, setInvitation] = useState<Invitation | null>(null);
  const [loading, setLoading] = useState(Boolean(token));
  const [error, setError] = useState('');
  const [fields, setFields] = useState<SignupFields>(emptyFields);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [created, setCreated] = useState(false);
  const busy = useRef(false);

  useEffect(() => {
    if (!token) return;
    let active = true;
    setLoading(true); setError(''); setInvitation(null);
    resolveInvitation(token).then(value => { if (active) setInvitation(value); })
      .catch((reason: unknown) => { if (active) setError(reason instanceof Error ? reason.message : 'Unable to load your invitation. Please try again.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, attempt]);

  function change(field: keyof SignupFields, value: string) {
    const next = { ...fields, [field]: value };
    setFields(next);
    if (errors[field] || (field === 'password' && errors.confirmation)) {
      const updated = validateSignup(next);
      setErrors(previous => ({ ...previous, [field]: updated[field],
        ...(field === 'password' && previous.confirmation ? { confirmation: updated.confirmation } : {}) }));
    }
  }

  function blur(field: keyof SignupFields) {
    setErrors(previous => ({ ...previous, [field]: validateSignup(fields)[field] }));
  }

  function useAnotherInvitation() {
    window.history.replaceState(null, '', window.location.pathname);
    setToken(''); setCode(''); setInvitation(null); setFields(emptyFields); setErrors({}); setError('');
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current || created) return;
    if (!invitation) { setError('Open a valid invitation before creating your account.'); return; }
    const validation = validateSignup(fields);
    setErrors(validation); setError('');
    if (Object.keys(validation).length) {
      const first = event.currentTarget.elements.namedItem(Object.keys(validation)[0]);
      if (first instanceof HTMLElement) first.focus();
      return;
    }
    busy.current = true; setSubmitting(true);
    try {
      await registerAccount({ fullName: fields.fullName.trim(), email: invitation.email, password: fields.password, invitationToken: token });
      setFields(emptyFields); setCreated(true);
    } catch (reason) {
      if (reason instanceof ApiError) {
        const mapped: FieldErrors = {};
        for (const name of ['fullName', 'email', 'password', 'confirmation'] as const) {
          if (reason.fields[name]) mapped[name] = reason.fields[name];
        }
        setErrors(mapped);
      }
      setError(reason instanceof Error ? reason.message : 'Registration could not be completed. Please try again.');
    } finally { busy.current = false; setSubmitting(false); }
  }

  return (
    <>
      <p className="eyebrow form-eyebrow">WELCOME ABOARD</p>
      <h2>Create your account</h2>
      <p className="intro">Your team is already here. Let's get you connected.</p>
      {error && <div className="error-notice" role="alert">{error}</div>}
      {loading ? <div className="notice" role="status">Checking your invitation...</div> : !invitation ? (
        <section className="invitation-entry">
          <span className="invitation-icon" aria-hidden="true">&#8599;</span>
          <h3>It starts with an invitation.</h3>
          <p>Open the link shared by your administrator, or enter your invitation code below.</p>
          <form onSubmit={event => { event.preventDefault(); setToken(code.trim()); setAttempt(value => value + 1); }}>
            <label htmlFor="invitation-code">Invitation code</label>
            <input id="invitation-code" value={code} onChange={event => setCode(event.target.value)} required autoComplete="off" spellCheck={false} />
            <button className="primary-button" type="submit">Continue with invitation <span aria-hidden="true">&#8594;</span></button>
          </form>
          {token && <button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Retry invitation</button>}
          <p className="invitation-help">No invitation? Ask your company administrator to invite you to DRIFT.</p>
        </section>
      ) : (
        <form onSubmit={submit} className="signup-form" noValidate aria-busy={submitting}>
          <div className="company-card"><span className="company-symbol" aria-hidden="true">&#9637;</span><div><span className="overline">YOU'RE JOINING</span><strong>{invitation.companyName}</strong></div><span className="verified-tag">Invited</span></div>
          <div className="field">
            <label htmlFor="full-name">Full name</label>
            <input id="full-name" name="fullName" autoComplete="name" placeholder="e.g. Alex Tan" value={fields.fullName} onChange={event => change('fullName', event.target.value)} onBlur={() => blur('fullName')} required maxLength={200} disabled={submitting || created} aria-invalid={Boolean(errors.fullName)} aria-describedby={errors.fullName ? 'full-name-error' : undefined} />
            {errors.fullName && <p className="field-error" id="full-name-error">{errors.fullName}</p>}
          </div>
          <div className="field">
            <label htmlFor="email">Work email <span className="locked-label">FROM YOUR INVITATION</span></label>
            <input id="email" name="email" type="email" value={invitation.email} autoComplete="email" readOnly aria-invalid={Boolean(errors.email)} aria-describedby={errors.email ? 'email-error' : 'email-hint'} />
            {errors.email ? <p className="field-error" id="email-error">{errors.email}</p> : <p className="field-hint" id="email-hint">Your email connects you to your company's workspace.</p>}
          </div>
          <div className="field"><label htmlFor="role">Your role <span className="locked-label">ASSIGNED</span></label><input id="role" value={roleNames[invitation.role]} readOnly /></div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <div className="password-input">
              <input id="password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder="Create a strong password" value={fields.password} onChange={event => change('password', event.target.value)} onBlur={() => blur('password')} required disabled={submitting || created} aria-invalid={Boolean(errors.password)} aria-describedby={errors.password ? 'password-error password-rules' : 'password-rules'} />
              <button type="button" className="visibility-button" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Hide' : 'Show'}</button>
            </div>
            {errors.password && <p className="field-error" id="password-error">{errors.password}</p>}
            <ul id="password-rules" className="password-rules" aria-label="Password requirements">{passwordRules(fields.password).map(rule => <li key={rule.label} data-met={rule.met}><span className="sr-only">{rule.met ? 'Met: ' : 'Required: '}</span>{rule.label}</li>)}</ul>
          </div>
          <div className="field">
            <label htmlFor="confirm-password">Confirm password</label>
            <input id="confirm-password" name="confirmation" type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder="Enter your password again" value={fields.confirmation} onChange={event => change('confirmation', event.target.value)} onBlur={() => blur('confirmation')} required disabled={submitting || created} aria-invalid={Boolean(errors.confirmation)} aria-describedby={errors.confirmation ? 'confirmation-error' : undefined} />
            {errors.confirmation && <p className="field-error" id="confirmation-error">{errors.confirmation}</p>}
          </div>
          {created ? <div className="success-notice" role="status">Your account has been created.</div> : <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Creating your account...' : 'Create account'}<span aria-hidden="true">&#8594;</span></button>}
          <p className="membership-note">Your company and role are set by your invitation.</p>
          {!created && <button type="button" className="retry-button" disabled={submitting} onClick={useAnotherInvitation}>Use a different invitation</button>}
        </form>
      )}
      <p className="login-prompt">Already part of the team? <a href="/login">Log in <span aria-hidden="true">&#8599;</span></a></p>
    </>
  );
}
