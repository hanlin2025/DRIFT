import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { homePath, sessionHasEnded, writeSession } from '../session/session';
import { login } from './api';

function registrationState(value: unknown): { registered: boolean; email: string } {
  if (typeof value === 'object' && value !== null && 'registered' in value && value.registered === true
    && 'email' in value && typeof value.email === 'string') {
    return { registered: true, email: value.email };
  }
  return { registered: false, email: '' };
}

function expiredState(value: unknown) {
  return typeof value === 'object' && value !== null && 'expired' in value && value.expired === true;
}

export function LoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const { registered, email: invitedEmail } = registrationState(location.state);
  const [email, setEmail] = useState(invitedEmail);
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({});
  const [error, setError] = useState(expiredState(location.state) || sessionHasEnded() ? 'Your session has ended. Log in again.' : '');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  useEffect(() => { document.title = 'Log in | DRIFT'; }, []);
  useEffect(() => { sessionStorage.removeItem('drift.session.ended'); }, []);

  function validate(next = { email, password }) {
    const result: { email?: string; password?: string } = {};
    if (!next.email.trim()) result.email = 'Email is required';
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(next.email.trim())) result.email = 'Email must be a valid email address';
    if (!next.password) result.password = 'Password is required';
    return result;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    const validation = validate();
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
      const session = await login(email.trim(), password);
      writeSession(session);
      setPassword('');
      navigate(homePath(session.role), { replace: true });
    } catch (reason) {
      if (reason instanceof ApiError) {
        const mapped: { email?: string; password?: string } = {};
        if (reason.fields.email) mapped.email = reason.fields.email;
        if (reason.fields.password) mapped.password = reason.fields.password;
        setErrors(mapped);
      }
      setError(reason instanceof Error ? reason.message : 'Login could not be completed. Please try again.');
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  return <>
    <p className="eyebrow form-eyebrow">YOUR NEXT CONNECTION</p>
    <h2>Welcome to DRIFT.</h2>
    <p className="intro">Your workspace starts with a shared view.</p>
    {registered && <div className="success-notice" role="status"><strong>Account created successfully.</strong><br />You're registered with {invitedEmail}.</div>}
    {error && <div className="error-notice" role="alert">{error}</div>}
    <form onSubmit={submit} className="login-fields" noValidate aria-busy={submitting}>
      <div className="field">
        <label htmlFor="login-email">Work email</label>
        <input id="login-email" name="email" type="email" autoComplete="email" value={email} placeholder="you@company.com" onChange={event => setEmail(event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.email)} aria-describedby={errors.email ? 'login-email-error' : undefined} />
        {errors.email && <p className="field-error" id="login-email-error">{errors.email}</p>}
      </div>
      <div className="field">
        <label htmlFor="login-password">Password</label>
        <div className="password-input">
          <input id="login-password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} placeholder="Enter your password" onChange={event => setPassword(event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.password)} aria-describedby={errors.password ? 'login-password-error' : undefined} />
          <button type="button" className="visibility-button" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Hide' : 'Show'}</button>
        </div>
        {errors.password && <p className="field-error" id="login-password-error">{errors.password}</p>}
      </div>
      <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Signing in...' : 'Log in'} <span aria-hidden="true">&#8594;</span></button>
    </form>
    <p className="login-prompt">Have another invitation? <Link to="/signup">Create an account</Link></p>
  </>;
}
