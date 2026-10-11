import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { loadSession } from '../login/api';
import { clearSession, endSession, homePath, isExpired, readSession, sessionHasEnded, shipmentDetailPath, shipmentListPath, writeSession, type Session } from '../session/session';
import { type ShipmentArchive } from '../shipment/archiveAccess';
import { listShipments, type Shipment } from '../shipment/api';
import { ShipmentDetail } from '../shipment/ShipmentDetail';
import { ShipmentForm } from '../shipment/ShipmentForm';
import { ShipmentImportPage } from '../shipment/ShipmentImportPage';
import { ApiError } from '../signup/api';
import { ShipmentList } from './ShipmentList';

function workspaceCopy(role: Session['role']) {
  if (role === 'IMPORTER') {
    return {
      eyebrow: 'IMPORTER',
      title: 'Shipment overview',
      intro: 'You are signed in to the shipments your company is authorised to follow.',
    };
  }
  if (role === 'FREIGHT_FORWARDER') {
    return {
      eyebrow: 'FREIGHT FORWARDER',
      title: 'Shipment portfolio',
      intro: 'You are signed in to the shipment plans you register and maintain.',
    };
  }
  return {
    eyebrow: role === 'ADMIN' ? 'ADMIN' : 'LOGISTICS MANAGER',
    title: 'Company shipments',
    intro: 'You are signed in to the shipments registered for your company.',
  };
}

function removedMessage(state: unknown) {
  if (typeof state !== 'object' || state === null || !('shipmentRemoved' in state)) return '';
  const message = state.shipmentRemoved;
  return typeof message === 'string' ? message : '';
}

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
  const navigate = useNavigate();
  const removed = removedMessage(useLocation().state);
  const [registered, setRegistered] = useState(0);
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; message: string } | null>(
    removed ? { tone: 'success', message: removed } : null,
  );
  const noticeRef = useRef<HTMLDivElement>(null);
  const copy = workspaceCopy(account.role);
  const canRegister = account.role === 'FREIGHT_FORWARDER';
  const canEdit = account.role === 'FREIGHT_FORWARDER' || account.role === 'IMPORTER';
  const listPath = shipmentListPath(account.role);
  const linkBase = account.role === 'ADMIN' ? '/admin' : listPath;
  const viewer = { role: account.role, companyId: account.company?.id ?? null };

  useEffect(() => {
    if (removed) setNotice({ tone: 'success', message: removed });
  }, [removed]);

  useEffect(() => {
    if (notice) noticeRef.current?.focus();
  }, [notice]);

  function archiveFailed(error: ApiError) {
    setNotice({ tone: 'error', message: error.message });
    if (error.status === 409) setRegistered(value => value + 1);
  }

  const archive: ShipmentArchive = {
    token: account.token,
    viewer,
    onSessionEnded,
    onRemoved: message => {
      setNotice({ tone: 'success', message });
      setRegistered(value => value + 1);
    },
    onFailed: archiveFailed,
  };

  return (
    <div className="workspace">
      <header className="workspace-bar">
        <Wordmark href={listPath} />
        {account.role === 'FREIGHT_FORWARDER' ? <Link className="workspace-nav" to="/freight-forwarder/import">Import shipments</Link> : null}
        {account.role === 'ADMIN' ? <Link className="workspace-nav" to="/admin">User management</Link> : null}
        <button type="button" className="sign-out" onClick={onSignOut}>Sign out</button>
      </header>
      <main className="workspace-main">
        {notice && <div ref={noticeRef} tabIndex={-1} className={notice.tone === 'success' ? 'success-notice' : 'error-notice'} role={notice.tone === 'success' ? 'status' : 'alert'}>{notice.message}</div>}
        {page === 'import'
          ? <ShipmentImportPage token={account.token} basePath={listPath} jobId={jobId} onSessionEnded={onSessionEnded} />
          : shipmentId
          ? <ShipmentDetail token={account.token} shipmentId={shipmentId} basePath={listPath} onSessionEnded={onSessionEnded} canEdit={canEdit} archive={{ ...archive, onRemoved: message => navigate(listPath, { state: { shipmentRemoved: message } }) }} />
          : <>
            <p className="eyebrow">{copy.eyebrow}</p>
            <h2>{copy.title}</h2>
            <p className="intro">{copy.intro}</p>
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
            <CompanyShipments token={account.token} basePath={linkBase} refreshKey={registered} onSessionEnded={onSessionEnded} archive={archive} />
            {canRegister ? <ShipmentForm token={account.token} companyName={account.company?.name ?? null} onSessionEnded={onSessionEnded} onRegistered={() => setRegistered(value => value + 1)} /> : null}
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
        navigate(shipmentId ? shipmentDetailPath(next.role, shipmentId) : homePath(next.role), { replace: true });
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

function CompanyShipments({ token, basePath, refreshKey, onSessionEnded, archive }: {
  token: string;
  basePath: string;
  refreshKey: number;
  onSessionEnded: () => void;
  archive: ShipmentArchive;
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
  return <ShipmentList shipments={shipments} basePath={basePath} archive={archive} />;
}
