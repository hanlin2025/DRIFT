import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { requestPasswordReset } from './api';
import { validateEmail } from './validation';

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState('');
  const [error, setError] = useState('');
  const [sent, setSent] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  useEffect(() => { document.title = 'Forgot password | DRIFT'; }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    const validation = validateEmail(email);
    setEmailError(validation);
    setError('');
    if (validation) {
      const field = event.currentTarget.elements.namedItem('email');
      if (field instanceof HTMLElement) field.focus();
      return;
    }
    busy.current = true;
    setSubmitting(true);
    try {
      setSent(await requestPasswordReset(email.trim()));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The reset link could not be requested. Please try again.');
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  return <>
    <p className="eyebrow form-eyebrow">ACCOUNT ACCESS</p>
    <h2>Forgot your password?</h2>
    <p className="intro">Enter the work email on your account. If it is registered, DRIFT sends a reset link.</p>
    {error && <div className="error-notice" role="alert">{error}</div>}
    {sent ? <>
      <div className="success-notice" role="status">{sent}</div>
      <p className="login-prompt">Ready to sign in? <Link to="/login">Log in</Link></p>
    </> : <form onSubmit={submit} className="login-fields" noValidate aria-busy={submitting}>
      <div className="field">
        <label htmlFor="forgot-email">Work email</label>
        <input id="forgot-email" name="email" type="email" autoComplete="email" value={email} placeholder="you@company.com" onChange={event => setEmail(event.target.value)} disabled={submitting} aria-invalid={Boolean(emailError)} aria-describedby={emailError ? 'forgot-email-error' : undefined} />
        {emailError && <p className="field-error" id="forgot-email-error">{emailError}</p>}
      </div>
      <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Sending the link...' : 'Send reset link'} <span aria-hidden="true">&#8594;</span></button>
    </form>}
    {!sent && <p className="login-prompt">Remembered it? <Link to="/login">Log in</Link></p>}
  </>;
}
