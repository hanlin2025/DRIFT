import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, verifyEmail } from './api';
import { ResendVerificationForm } from './CreateCompanyForm';

const TOKEN = /^[A-Za-z0-9_-]{43}$/;
export const INVALID_VERIFICATION_LINK = 'This verification link is invalid or has expired. Please request a new one.';
export const INCOMPLETE_VERIFICATION_LINK = 'This verification link is incomplete. Open the full link from your email, or request a new one.';

function tokenFrom(hash: string) {
  return new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash).get('token') ?? '';
}

type State = { kind: 'checking' } | { kind: 'verified'; message: string } | { kind: 'invalid'; message: string }
  | { kind: 'incomplete' } | { kind: 'failed'; message: string };

export function VerifyEmailPage() {
  const [token] = useState(() => tokenFrom(window.location.hash));
  const [state, setState] = useState<State>(() => (token ? { kind: 'checking' } : { kind: 'incomplete' }));
  const [attempt, setAttempt] = useState(0);
  const started = useRef<number | null>(null);

  useEffect(() => {
    document.title = 'Verify email | DRIFT';
    // The token must not stay in the address bar or browser history.
    if (window.location.hash) {
      window.history.replaceState(window.history.state, '', `${window.location.pathname}${window.location.search}`);
    }
  }, []);

  useEffect(() => {
    if (!token || started.current === attempt) return;
    started.current = attempt;
    if (!TOKEN.test(token)) {
      setState({ kind: 'invalid', message: INVALID_VERIFICATION_LINK });
      return;
    }
    setState({ kind: 'checking' });
    verifyEmail(token)
      .then(result => setState({ kind: 'verified', message: result.message }))
      .catch((reason: unknown) => {
        if (reason instanceof ApiError && reason.status === 400) setState({ kind: 'invalid', message: reason.message });
        else if (reason instanceof ApiError && reason.status === 429) setState({ kind: 'failed', message: reason.message });
        else setState({ kind: 'failed', message: 'We could not reach DRIFT to verify your email. Check your connection and try again.' });
      });
  }, [token, attempt]);

  return <>
    <p className="eyebrow form-eyebrow">ACCOUNT ACCESS</p>
    <h1>Verify your email</h1>
    {state.kind === 'checking' && <div className="notice" role="status">Verifying your email...</div>}
    {state.kind === 'verified' && <>
      <div className="success-notice" role="status">{state.message}</div>
      <p className="login-prompt"><Link to="/login">Log in</Link></p>
    </>}
    {state.kind === 'failed' && <>
      <div className="error-notice" role="alert">{state.message}<br />
        <button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button>
      </div>
    </>}
    {(state.kind === 'invalid' || state.kind === 'incomplete') && <>
      <div className="error-notice" role="alert">{state.kind === 'invalid' ? state.message : INCOMPLETE_VERIFICATION_LINK}</div>
      <p className="intro">Enter the email you signed up with and we will send a new link if it is still awaiting verification.</p>
      <ResendVerificationForm />
      <p className="login-prompt">Verified already? <Link to="/login">Log in</Link></p>
    </>}
  </>;
}
