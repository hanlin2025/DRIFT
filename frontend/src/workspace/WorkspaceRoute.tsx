import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { loadSession } from '../login/api';
import { clearSession, endSession, homePath, isExpired, readSession, sessionHasEnded, writeSession, type Session } from '../session/session';
import { listShipments, type Shipment } from '../shipment/api';
import { ShipmentDetail } from '../shipment/ShipmentDetail';
import { ShipmentForm } from '../shipment/ShipmentForm';
import { ShipmentImportPage } from '../shipment/ShipmentImportPage';
import { ApiError } from '../signup/api';
import { ShipmentList } from './ShipmentList';

function Wordmark({ href }: { href: string }) {
  return (
    <a className="wordmark" href={href} aria-label="DRIFT home">
      <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
      DRIFT<span className="wordmark-dot">.</span>
    </a>
  );
}

export function WorkspacePage({ account, onSignOut, onSessionEnded, page = 'portfolio' }: {
  account: Session;
  onSignOut: () => void;
  onSessionEnded: () => void;
  page?: 'portfolio' | 'import';
}) {
  const { shipmentId, jobId } = useParams();
  const [registered, setRegistered] = useState(0);
  const importer = account.role === 'IMPORTER';
  const basePath = homePath(account.role);
  return (
    <div className="workspace">
      <header className="workspace-bar">
        <Wordmark href={basePath} />
        {account.role === 'FREIGHT_FORWARDER' ? <Link className="workspace-nav" to="/freight-forwarder/import">Import shipments</Link> : null}
        {account.role === 'ADMIN' ? <Link className="workspace-nav" to="/admin">User management</Link> : null}
        <button type="button" className="sign-out" onClick={onSignOut}>Sign out</button>
      </header>
      <main className="workspace-main">
        {page === 'import'
          ? <ShipmentImportPage token={account.token} basePath={basePath} jobId={jobId} onSessionEnded={onSessionEnded} />
          : shipmentId
          ? <ShipmentDetail token={account.token} shipmentId={shipmentId} basePath={basePath} onSessionEnded={onSessionEnded} />
          : <>
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
            <CompanyShipments token={account.token} basePath={basePath} refreshKey={registered} onSessionEnded={onSessionEnded} />
            {importer ? null : <ShipmentForm token={account.token} companyName={account.company?.name ?? null} onSessionEnded={onSessionEnded} onRegistered={() => setRegistered(value => value + 1)} />}
          </>}
      </main>
    </div>
  );
}

// setTimeout fires immediately for delays above 2^31 - 1 ms, so long sessions are re-checked in steps.
const MAX_TIMER_DELAY = 2_147_483_647;

export function WorkspaceRoute({ role, page = 'portfolio' }: { role: Session['role']; page?: 'portfolio' | 'import' }) {
  const navigate = useNavigate();
  const { shipmentId } = useParams();
  const [account, setAccount] = useState<Session | null>(null);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);
  const sessionEnded = useCallback(() => {
    endSession();
    navigate('/login', { replace: true, state: { expired: true } });
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
      if (page === 'import' && next.role !== 'FREIGHT_FORWARDER') {
        navigate(homePath(next.role), { replace: true });
        return;
      }
      if (homePath(next.role) !== homePath(role)) {
        const home = homePath(next.role);
        navigate(shipmentId && home !== '/admin' ? `${home}/shipments/${shipmentId}` : home, { replace: true });
        return;
      }
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
  }, [navigate, page, role, attempt, sessionEnded, shipmentId]);

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

  return <WorkspacePage account={account} page={page} onSignOut={() => { clearSession(); navigate('/login', { replace: true }); }} onSessionEnded={sessionEnded} />;
}

function CompanyShipments({ token, basePath, refreshKey, onSessionEnded }: {
  token: string;
  basePath: string;
  refreshKey: number;
  onSessionEnded: () => void;
}) {
  const [shipments, setShipments] = useState<Shipment[] | null>(null);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setProblem('');
    setShipments(null);
    listShipments(token).then(found => {
      if (active) setShipments(found);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    });
    return () => { active = false; };
  }, [token, refreshKey, attempt, onSessionEnded]);

  if (problem) {
    return <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></div>;
  }
  if (shipments === null) return <div className="notice">Loading shipments...</div>;
  if (shipments.length === 0) {
    return <div className="notice">No shipments yet. Shipments registered for your company will appear here.</div>;
  }
  return <ShipmentList shipments={shipments} basePath={basePath} />;
}
