import { useRef, useState, type FormEvent } from 'react';
import { ApiError, registerCompany, resendVerification } from './api';
import { passwordRules, validateCompanySignup, type CompanySignupErrors, type CompanySignupFields } from './validation';

const empty: CompanySignupFields = { companyCode: '', companyName: '', fullName: '', email: '', password: '', confirmation: '' };
const FIELD_ORDER: (keyof CompanySignupFields)[] = ['companyCode', 'companyName', 'fullName', 'email', 'password', 'confirmation'];
const RETRY = 'We could not complete your signup. Your details are still here, so you can try again.';

export function CreateCompanyForm({ onBack }: { onBack: () => void }) {
  const [fields, setFields] = useState<CompanySignupFields>(empty);
  const [errors, setErrors] = useState<CompanySignupErrors>({});
  const [error, setError] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [sentTo, setSentTo] = useState('');
  const busy = useRef(false);

  function change(field: keyof CompanySignupFields, value: string) {
    const next = { ...fields, [field]: value };
    setFields(next);
    if (errors[field] || (field === 'password' && errors.confirmation)) {
      const updated = validateCompanySignup(next);
      setErrors(previous => ({ ...previous, [field]: updated[field],
        ...(field === 'password' && previous.confirmation ? { confirmation: updated.confirmation } : {}) }));
    }
  }

  function blur(field: keyof CompanySignupFields) {
    setErrors(previous => ({ ...previous, [field]: validateCompanySignup(fields)[field] }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    const validation = validateCompanySignup(fields);
    setErrors(validation);
    setError('');
    const firstInvalid = FIELD_ORDER.find(name => validation[name]);
    if (firstInvalid) {
      const element = event.currentTarget.elements.namedItem(firstInvalid);
      if (element instanceof HTMLElement) element.focus();
      return;
    }
    busy.current = true;
    setSubmitting(true);
    try {
      const email = fields.email.trim();
      await registerCompany({
        companyCode: fields.companyCode.trim(), companyName: fields.companyName.trim(),
        fullName: fields.fullName.trim(), email, password: fields.password,
      });
      setFields(empty);
      setSentTo(email);
    } catch (reason) {
      if (reason instanceof ApiError && (reason.status === 400 || reason.status === 409)) {
        const mapped: CompanySignupErrors = {};
        for (const name of FIELD_ORDER) if (reason.fields[name]) mapped[name] = reason.fields[name];
        setErrors(mapped);
        setError(Object.keys(mapped).length ? '' : reason.message);
      } else if (reason instanceof ApiError && reason.status === 429) {
        setError(`${reason.message} Your details are still here.`);
      } else {
        setError(RETRY);
      }
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  if (sentTo) return <CheckYourEmail email={sentTo} />;

  const describedBy = (field: keyof CompanySignupFields, extra?: string) =>
    [errors[field] ? `company-${field}-error` : '', extra ?? ''].filter(Boolean).join(' ') || undefined;
  const fieldError = (field: keyof CompanySignupFields) =>
    errors[field] ? <p className="field-error" id={`company-${field}-error`}>{errors[field]}</p> : null;
  const disabled = submitting;

  return (
    <form onSubmit={submit} className="signup-form" noValidate aria-busy={submitting} aria-labelledby="create-company-heading">
      <h2 id="create-company-heading" className="form-section-title">Create a company</h2>
      <p className="intro">You will be the company's administrator. We email you a link to verify your address before you can sign in.</p>
      {error && <div className="error-notice" role="alert">{error}</div>}
      <div className="field">
        <label htmlFor="company-code">Company code</label>
        <input id="company-code" name="companyCode" autoComplete="off" spellCheck={false} placeholder="e.g. PACIFIC_GATE" value={fields.companyCode} onChange={event => change('companyCode', event.target.value)} onBlur={() => blur('companyCode')} maxLength={50} disabled={disabled} aria-invalid={Boolean(errors.companyCode)} aria-describedby={describedBy('companyCode', 'company-code-hint')} />
        {fieldError('companyCode')}
        <p className="field-hint" id="company-code-hint">Letters, digits and underscores, starting with a letter. Stored in capitals.</p>
      </div>
      <div className="field">
        <label htmlFor="company-name">Company name</label>
        <input id="company-name" name="companyName" autoComplete="organization" placeholder="e.g. Pacific Gate Imports" value={fields.companyName} onChange={event => change('companyName', event.target.value)} onBlur={() => blur('companyName')} maxLength={200} disabled={disabled} aria-invalid={Boolean(errors.companyName)} aria-describedby={describedBy('companyName')} />
        {fieldError('companyName')}
      </div>
      <div className="field">
        <label htmlFor="company-full-name">Full name</label>
        <input id="company-full-name" name="fullName" autoComplete="name" placeholder="e.g. Alex Tan" value={fields.fullName} onChange={event => change('fullName', event.target.value)} onBlur={() => blur('fullName')} maxLength={200} disabled={disabled} aria-invalid={Boolean(errors.fullName)} aria-describedby={describedBy('fullName')} />
        {fieldError('fullName')}
      </div>
      <div className="field">
        <label htmlFor="company-email">Work email</label>
        <input id="company-email" name="email" type="email" autoComplete="email" placeholder="you@company.com" value={fields.email} onChange={event => change('email', event.target.value)} onBlur={() => blur('email')} maxLength={320} disabled={disabled} aria-invalid={Boolean(errors.email)} aria-describedby={describedBy('email')} />
        {fieldError('email')}
      </div>
      <div className="field">
        <label htmlFor="company-password">Password</label>
        <div className="password-input">
          <input id="company-password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder="Create a strong password" value={fields.password} onChange={event => change('password', event.target.value)} onBlur={() => blur('password')} disabled={disabled} aria-invalid={Boolean(errors.password)} aria-describedby={describedBy('password', 'company-password-rules')} />
          <button type="button" className="visibility-button" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Hide' : 'Show'}</button>
        </div>
        {fieldError('password')}
        <ul id="company-password-rules" className="password-rules" aria-label="Password requirements">{passwordRules(fields.password).map(rule => <li key={rule.label} data-met={rule.met}><span className="sr-only">{rule.met ? 'Met: ' : 'Required: '}</span>{rule.label}</li>)}</ul>
      </div>
      <div className="field">
        <label htmlFor="company-confirmation">Confirm password</label>
        <input id="company-confirmation" name="confirmation" type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder="Enter your password again" value={fields.confirmation} onChange={event => change('confirmation', event.target.value)} onBlur={() => blur('confirmation')} disabled={disabled} aria-invalid={Boolean(errors.confirmation)} aria-describedby={describedBy('confirmation')} />
        {fieldError('confirmation')}
      </div>
      <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Creating your company...' : 'Create company'} <span aria-hidden="true">&#8594;</span></button>
      <button type="button" className="retry-button" disabled={submitting} onClick={onBack}>Back to the signup choices</button>
    </form>
  );
}

export function ResendVerificationForm({ initialEmail = '', label = 'Send a new verification link' }: { initialEmail?: string; label?: string }) {
  const [email, setEmail] = useState(initialEmail);
  const [message, setMessage] = useState('');
  const [problem, setProblem] = useState('');
  const [sending, setSending] = useState(false);
  const busy = useRef(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    const value = email.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
      setProblem('Enter a valid email address.');
      setMessage('');
      return;
    }
    busy.current = true;
    setSending(true);
    setProblem('');
    setMessage('');
    try {
      setMessage((await resendVerification(value)).message);
    } catch (reason) {
      setProblem(reason instanceof ApiError && reason.status !== 0 && reason.status < 500 ? reason.message : 'We could not send a new link. Please try again.');
    } finally {
      busy.current = false;
      setSending(false);
    }
  }

  return (
    <form className="login-fields resend-verification" onSubmit={submit} noValidate aria-busy={sending}>
      {message && <div className="success-notice" role="status">{message}</div>}
      <div className="field">
        <label htmlFor="resend-email">Signup email</label>
        <input id="resend-email" name="email" type="email" autoComplete="email" value={email} onChange={event => setEmail(event.target.value)} disabled={sending} aria-invalid={Boolean(problem)} aria-describedby={problem ? 'resend-email-error' : undefined} />
        {problem && <p className="field-error" id="resend-email-error" role="alert">{problem}</p>}
      </div>
      <button type="submit" className="primary-button" disabled={sending}>{sending ? 'Sending...' : label}</button>
    </form>
  );
}

function CheckYourEmail({ email }: { email: string }) {
  return (
    <section aria-labelledby="check-email-heading">
      <div className="success-notice" role="status">
        <strong id="check-email-heading">Check your email.</strong><br />
        Check your email to verify your account before signing in. If {email} can be used for a new company, a verification link is on its way.
      </div>
      <p className="intro">Didn't receive it? Send a new link.</p>
      <ResendVerificationForm initialEmail={email} label="Resend verification email" />
      <p className="login-prompt">Verified already? <a href="/login">Log in</a></p>
    </section>
  );
}
