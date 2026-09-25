import { useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';

function registrationState(value: unknown): { registered: boolean; email: string } {
  if (typeof value === 'object' && value !== null && 'registered' in value && value.registered === true
    && 'email' in value && typeof value.email === 'string') {
    return { registered: true, email: value.email };
  }
  return { registered: false, email: '' };
}

export function LoginPage() {
  const location = useLocation();
  const { registered, email } = registrationState(location.state);
  useEffect(() => { document.title = 'Log in | DRIFT'; }, []);

  return <>
    <p className="eyebrow form-eyebrow">YOUR NEXT CONNECTION</p>
    <h2>Welcome to DRIFT.</h2>
    <p className="intro">Your workspace starts with a shared view.</p>
    {registered && <div className="success-notice" role="status"><strong>Account created successfully.</strong><br />You're registered with {email}.</div>}
    <div className="notice" id="login-availability">Login is not available yet. Your account is ready for when sign-in opens.</div>
    <fieldset className="login-fields" disabled aria-describedby="login-availability">
      <div className="field"><label htmlFor="login-email">Work email</label><input id="login-email" type="email" autoComplete="email" defaultValue={email} placeholder="you@company.com" /></div>
      <div className="field"><label htmlFor="login-password">Password</label><input id="login-password" type="password" autoComplete="current-password" placeholder="Enter your password" /></div>
      <button type="button" className="primary-button">Log in <span aria-hidden="true">&#8594;</span></button>
    </fieldset>
    <p className="login-prompt">Have another invitation? <Link to="/signup">Create an account</Link></p>
  </>;
}
