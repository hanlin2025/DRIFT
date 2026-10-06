import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { passwordRules } from '../signup/validation';
import { submitPasswordReset } from './api';
import { RESET_LINK_MESSAGE, resetToken, validateReset } from './validation';

export function ResetPasswordPage() {
  const navigate = useNavigate();
  const token = resetToken(window.location.hash);
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [errors, setErrors] = useState<{ password?: string; confirmation?: string }>({});
  const [error, setError] = useState(token ? '' : RESET_LINK_MESSAGE);
  const linkRejected = error.includes(RESET_LINK_MESSAGE);
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  useEffect(() => { document.title = 'Reset password | DRIFT'; }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current || !token) return;
    const validation = validateReset({ password, confirmation });
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
      const message = await submitPasswordReset(token, password);
      setPassword('');
      setConfirmation('');
      navigate('/login', { replace: true, state: { passwordReset: message } });
    } catch (reason) {
      if (reason instanceof ApiError && reason.fields.password) setErrors({ password: reason.fields.password });
      setError(reason instanceof ApiError && reason.fields.password ? '' : reason instanceof Error ? reason.message : 'The password could not be reset. Please try again.');
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  return <>
    <p className="eyebrow form-eyebrow">ACCOUNT ACCESS</p>
    <h2>Choose a new password</h2>
    <p className="intro">This link sets a new password for the account that requested it.</p>
    {error && <div className="error-notice" role="alert">{error}</div>}
    {token && !linkRejected ? <form onSubmit={submit} className="login-fields" noValidate aria-busy={submitting}>
      <div className="field">
        <label htmlFor="reset-password">New password</label>
        <div className="password-input">
          <input id="reset-password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={password} placeholder="Create a strong password" onChange={event => setPassword(event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.password)} aria-describedby={errors.password ? 'reset-password-error reset-password-rules' : 'reset-password-rules'} />
          <button type="button" className="visibility-button" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Hide' : 'Show'}</button>
        </div>
        {errors.password && <p className="field-error" id="reset-password-error">{errors.password}</p>}
        <ul id="reset-password-rules" className="password-rules" aria-label="Password requirements">{passwordRules(password).map(rule => <li key={rule.label} data-met={rule.met}><span className="sr-only">{rule.met ? 'Met: ' : 'Required: '}</span>{rule.label}</li>)}</ul>
      </div>
      <div className="field">
        <label htmlFor="reset-confirmation">Confirm password</label>
        <input id="reset-confirmation" name="confirmation" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={confirmation} placeholder="Enter your password again" onChange={event => setConfirmation(event.target.value)} disabled={submitting} aria-invalid={Boolean(errors.confirmation)} aria-describedby={errors.confirmation ? 'reset-confirmation-error' : undefined} />
        {errors.confirmation && <p className="field-error" id="reset-confirmation-error">{errors.confirmation}</p>}
      </div>
      <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Saving the password...' : 'Reset password'} <span aria-hidden="true">&#8594;</span></button>
    </form> : null}
    <p className="login-prompt">{linkRejected ? <Link to="/forgot-password">Request a new link</Link> : <>Remembered it? <Link to="/login">Log in</Link></>}</p>
  </>;
}
