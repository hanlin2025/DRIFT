import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { loadSession } from '../login/api';
import { clearSession, endSession, homePath, isExpired, readSession, sessionHasEnded, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { ProfilePage } from './ProfilePage';

function Wordmark({ href }: { href: string }) {
  return (
    <a className="wordmark" href={href} aria-label="DRIFT home">
      <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
      DRIFT<span className="wordmark-dot">.</span>
    </a>
  );
}

const MAX_TIMER_DELAY = 2_147_483_647;

export function ProfileRoute() {
  const navigate = useNavigate();
  const [account, setAccount] = useState<Session | null>(null);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);
  const sessionEnded = useCallback(() => {
    endSession();
    navigate('/login', { replace: true, state: { expired: true } });
  }, [navigate]);
  const passwordChanged = useCallback(() => {
    endSession();
    navigate('/login', { replace: true, state: { passwordChanged: true } });
  }, [navigate]);

  useEffect(() => {
    const saved = readSession();
    if (!saved || isExpired(saved.expiresAt)) {
      const expired = Boolean(saved) || sessionHasEnded();
      if (saved) endSession();
      navigate('/login', { replace: true, state: expired ? { expired: true } : undefined });
      return;
    }
    let active = true;
    setProblem('');
    loadSession(saved.token).then(current => {
      if (!active) return;
      const next = { ...current, token: saved.token };
      writeSession(next);
      setAccount(next);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        sessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    });
    return () => { active = false; };
  }, [navigate, attempt, sessionEnded]);

  useEffect(() => {
    if (!account) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const check = () => {
      if (isExpired(account.expiresAt)) {
        endSession();
        navigate('/login', { replace: true, state: { expired: true } });
        return;
      }
      timer = setTimeout(check, Math.min(Date.parse(account.expiresAt) - Date.now(), MAX_TIMER_DELAY));
    };
    check();
    return () => clearTimeout(timer);
  }, [account, navigate]);

  if (!account) return <div className="workspace"><main className="workspace-main">{problem
    ? <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></div>
    : <div className="notice" role="status">Checking your session...</div>}</main></div>;

  const home = homePath(account.role);
  return (
    <div className="workspace">
      <header className="workspace-bar">
        <Wordmark href={home} />
        {account.role === 'ADMIN' ? <Link className="workspace-nav" to="/admin">User management</Link> : null}
        <Link className="workspace-nav" to="/profile" aria-current="page">Profile</Link>
        <button type="button" className="sign-out" onClick={() => { clearSession(); navigate('/login', { replace: true }); }}>Sign out</button>
      </header>
      <main className="workspace-main">
        <ProfilePage token={account.token} onSessionEnded={sessionEnded} onPasswordChanged={passwordChanged} onNameSaved={fullName => {
          setAccount(current => {
            if (!current) return current;
            const next = { ...current, fullName };
            writeSession(next);
            return next;
          });
        }} />
      </main>
    </div>
  );
}
