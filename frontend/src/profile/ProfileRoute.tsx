import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { loadSession } from '../login/api';
import { endSession, isExpired, readSession, sessionHasEnded, signOut, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { WorkspaceFrame } from '../workspace/WorkspaceFrame';
import { ProfilePage } from './ProfilePage';

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

  return (
    <WorkspaceFrame account={account} section="profile" onSignOut={() => { void signOut().then(() => navigate('/login', { replace: true })); }}>
      <ProfilePage token={account.token} onSessionEnded={sessionEnded} onPasswordChanged={passwordChanged} onNameSaved={fullName => {
        setAccount(current => {
          if (!current) return current;
          const next = { ...current, fullName };
          writeSession(next);
          return next;
        });
      }} />
    </WorkspaceFrame>
  );
}
