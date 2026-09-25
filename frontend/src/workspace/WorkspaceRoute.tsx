import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { loadSession } from '../login/api';
import { clearSession, homePath, isExpired, readSession, writeSession, type Session } from '../session/session';

function Wordmark({ href }: { href: string }) {
  return (
    <a className="wordmark" href={href} aria-label="DRIFT home">
      <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
      DRIFT<span className="wordmark-dot">.</span>
    </a>
  );
}

export function WorkspacePage({ account, onSignOut }: { account: Session; onSignOut: () => void }) {
  const importer = account.role === 'IMPORTER';
  return (
    <div className="workspace">
      <header className="workspace-bar">
        <Wordmark href={homePath(account.role)} />
        <button type="button" className="sign-out" onClick={onSignOut}>Sign out</button>
      </header>
      <main className="workspace-main">
        <p className="eyebrow">{importer ? 'IMPORTER' : 'FREIGHT FORWARDER'}</p>
        <h2>{importer ? 'Shipment overview' : 'Shipment portfolio'}</h2>
        <p className="intro">{importer
          ? 'You are signed in to the shipments your company is authorised to follow.'
          : 'You are signed in to the shipment plans you register and maintain.'}</p>
        <div className="company-card">
          <span className="company-symbol" aria-hidden="true">&#9637;</span>
          <div>
            <span className="overline">COMPANY</span>
            <strong>{account.company?.name ?? 'No company assigned'}</strong>
          </div>
        </div>
        <dl className="session-facts">
          <div><dt>Signed in as</dt><dd>{account.fullName}</dd></div>
          <div><dt>Email</dt><dd>{account.email}</dd></div>
        </dl>
      </main>
    </div>
  );
}

export function WorkspaceRoute({ role }: { role: Session['role'] }) {
  const navigate = useNavigate();
  const [account, setAccount] = useState<Session | null>(null);

  useEffect(() => {
    const saved = readSession();
    if (!saved || isExpired(saved.expiresAt)) {
      const expired = Boolean(saved);
      clearSession();
      navigate('/login', { replace: true, state: expired ? { expired: true } : undefined });
      return;
    }
    let active = true;
    loadSession(saved.token).then(current => {
      if (!active) return;
      const next = { ...current, token: saved.token };
      writeSession(next);
      if (next.role !== role) {
        navigate(homePath(next.role), { replace: true });
        return;
      }
      setAccount(next);
    }).catch(() => {
      if (!active) return;
      clearSession();
      navigate('/login', { replace: true, state: { expired: true } });
    });
    return () => { active = false; };
  }, [navigate, role]);

  if (!account) return <div className="workspace"><main className="workspace-main"><div className="notice" role="status">Checking your session...</div></main></div>;

  return <WorkspacePage account={account} onSignOut={() => { clearSession(); navigate('/login', { replace: true }); }} />;
}
