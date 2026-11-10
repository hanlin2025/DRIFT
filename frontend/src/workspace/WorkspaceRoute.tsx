import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { loadSession } from '../login/api';
import { endSession, homePath, isExpired, readSession, sessionHasEnded, shipmentDetailPath, shipmentListPath, signOut, writeSession, type Session } from '../session/session';
import { type ShipmentArchive } from '../shipment/archiveAccess';
import { listShipments, type Shipment } from '../shipment/api';
import { ShipmentDetailEntry } from '../shipment/ShipmentDetailEntry';
import { ShipmentForm } from '../shipment/ShipmentForm';
import { ShipmentImportPage } from '../shipment/ShipmentImportPage';
import { applyShipmentFilters, EMPTY_SHIPMENT_FILTERS, hasShipmentFilters, shipmentFiltersFromSearchParams, type ShipmentFilters } from '../shipment/shipmentFilters';
import { ApiError } from '../signup/api';
import { ShipmentFilterControls } from './ShipmentFilterControls';
import { ShipmentList } from './ShipmentList';

function workspaceCopy(role: Session['role']) {
  if (role === 'IMPORTER') {
    return { eyebrow: 'IMPORTER', title: 'Shipment overview' };
  }
  if (role === 'FREIGHT_FORWARDER') {
    return { eyebrow: 'FREIGHT FORWARDER', title: 'Shipment portfolio' };
  }
  return {
    eyebrow: role === 'ADMIN' ? 'ADMIN' : 'LOGISTICS MANAGER',
    title: 'Company shipments',
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
      <span className="wordmark-name">DRIFT<span className="wordmark-dot">.</span></span>
    </a>
  );
}

type NavIconName = 'shipments' | 'import' | 'users' | 'profile' | 'sign-out' | 'panel';

function NavIcon({ name }: { name: NavIconName }) {
  const common = { viewBox: '0 0 24 24', 'aria-hidden': true as const, className: 'nav-icon' };
  switch (name) {
    case 'shipments':
      return <svg {...common}><path d="M4 7h16M4 12h16M4 17h10" /></svg>;
    case 'import':
      return <svg {...common}><path d="M12 4v10m0 0 3.5-3.5M12 14 8.5 10.5M5 20h14" /></svg>;
    case 'users':
      return <svg {...common}><circle cx="9" cy="8" r="2.5" /><circle cx="16" cy="9" r="2" /><path d="M4.5 18c.8-2.4 2.6-3.5 4.5-3.5s3.7 1.1 4.5 3.5M13 14.6c1.2-.7 2.5-1 3.6-1 1.6 0 3 .8 3.8 2.6" /></svg>;
    case 'profile':
      return <svg {...common}><circle cx="12" cy="8" r="3" /><path d="M5.5 19c1.2-2.8 3.3-4 6.5-4s5.3 1.2 6.5 4" /></svg>;
    case 'sign-out':
      return <svg {...common}><path d="M10 7V5h9v14h-9v-2M4 12h11m0 0-3-3m3 3-3 3" /></svg>;
    case 'panel':
      return <svg {...common}><rect x="4" y="4" width="16" height="16" rx="2" /><path d="M9 4v16" /></svg>;
    default: {
      const exhaustive: never = name;
      return exhaustive;
    }
  }
}

function readSidebarCollapsed() {
  try {
    return sessionStorage.getItem('drift.sidebar') === 'icons';
  } catch {
    return false;
  }
}

export function WorkspacePage({ account, onSignOut, onSessionEnded, page = 'portfolio' }: {
  account: Session;
  onSignOut: () => void;
  onSessionEnded: () => void;
  page?: 'portfolio' | 'import' | 'register';
}) {
  const { shipmentId, jobId } = useParams();
  const navigate = useNavigate();
  const removed = removedMessage(useLocation().state);
  const [searchParams, setSearchParams] = useSearchParams();
  const [registered, setRegistered] = useState(0);
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; message: string } | null>(
    removed ? { tone: 'success', message: removed } : null,
  );
  const noticeRef = useRef<HTMLDivElement>(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(readSidebarCollapsed);
  const copy = workspaceCopy(account.role);
  const canRegister = account.role === 'FREIGHT_FORWARDER';
  const canEdit = account.role === 'FREIGHT_FORWARDER' || account.role === 'IMPORTER';
  const controlledScenarioEnabled = import.meta.env.VITE_CONTROLLED_SCENARIO_ENABLED === 'true';
  const listPath = shipmentListPath(account.role);
  const linkBase = account.role === 'ADMIN' ? '/admin' : listPath;
  const viewer = { role: account.role, companyId: account.company?.id ?? null };
  const filters = shipmentFiltersFromSearchParams(searchParams);
  const clearFilters = () => setSearchParams(applyShipmentFilters(searchParams, EMPTY_SHIPMENT_FILTERS));

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
    <div className={sidebarCollapsed ? 'workspace is-sidebar-collapsed' : 'workspace'}>
      <header className="workspace-bar">
        <div className="workspace-bar-head">
          <Wordmark href={listPath} />
          <button
            type="button"
            className="sidebar-toggle"
            aria-expanded={!sidebarCollapsed}
            aria-label={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            onClick={() => setSidebarCollapsed(current => {
              const next = !current;
              sessionStorage.setItem('drift.sidebar', next ? 'icons' : 'open');
              return next;
            })}
          >
            <NavIcon name="panel" />
          </button>
        </div>
        <p className="workspace-identity">
          <strong>{account.fullName}</strong>
          <span>{account.company?.name ?? 'No company assigned'}</span>
        </p>
        <Link className="workspace-nav" to={listPath} aria-current={page === 'import' ? undefined : 'page'}><NavIcon name="shipments" /><span className="nav-label">{account.role === 'ADMIN' ? 'Company shipments' : 'Shipments'}</span></Link>
        {account.role === 'FREIGHT_FORWARDER' ? <Link className="workspace-nav" to="/freight-forwarder/import" aria-current={page === 'import' ? 'page' : undefined}><NavIcon name="import" /><span className="nav-label">Import shipments</span></Link> : null}
        {account.role === 'ADMIN' ? <Link className="workspace-nav" to="/admin"><NavIcon name="users" /><span className="nav-label">User management</span></Link> : null}
        <Link className="workspace-nav" to="/profile"><NavIcon name="profile" /><span className="nav-label">Profile</span></Link>
        <button type="button" className="sign-out" onClick={onSignOut}><NavIcon name="sign-out" /><span className="nav-label">Sign out</span></button>
      </header>
      <main className="workspace-main">
        {notice && <div ref={noticeRef} tabIndex={-1} className={notice.tone === 'success' ? 'success-notice' : 'error-notice'} role={notice.tone === 'success' ? 'status' : 'alert'}>{notice.message}</div>}
        {page === 'import'
          ? <ShipmentImportPage token={account.token} basePath={listPath} jobId={jobId} onSessionEnded={onSessionEnded} />
          : page === 'register'
          ? <section className="shipment-step">
              <Link className="back-link" to={listPath}><span aria-hidden="true">&#8592;</span> Back to shipments</Link>
              <ShipmentForm token={account.token} companyName={account.company?.name ?? null} onSessionEnded={onSessionEnded} onRegistered={() => setRegistered(value => value + 1)} />
            </section>
          : shipmentId
          ? <ShipmentDetailEntry key={`${account.id}:${account.company?.id ?? 'none'}:${shipmentId}`} viewer={account}
              shipmentId={shipmentId} basePath={listPath} onSessionEnded={onSessionEnded} canEdit={canEdit}
              archive={{ ...archive, onRemoved: message => navigate(listPath, { state: { shipmentRemoved: message } }) }}
              controlledScenarioEnabled={controlledScenarioEnabled} />
          : <>
            <div className="page-heading">
              <div>
                <p className="eyebrow">{copy.eyebrow}</p>
                <h2>{copy.title}</h2>
              </div>
              {canRegister ? <Link className="primary-button page-action" to={`${listPath}/shipments/new`}>Register a shipment</Link> : null}
            </div>
            <ShipmentFilterControls />
            <CompanyShipments token={account.token} basePath={linkBase} refreshKey={registered} filters={filters} onClearFilters={clearFilters} onSessionEnded={onSessionEnded} archive={archive} />
          </>}
      </main>
    </div>
  );
}

// setTimeout fires immediately for delays above 2^31 - 1 ms, so long sessions are re-checked in steps.
const MAX_TIMER_DELAY = 2_147_483_647;

export function WorkspaceRoute({ role, page = 'portfolio' }: { role: Session['role']; page?: 'portfolio' | 'import' | 'register' }) {
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
      if (page === 'register' && next.role !== 'FREIGHT_FORWARDER') {
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

  return <WorkspacePage account={account} page={page} onSignOut={() => { void signOut().then(() => navigate('/login', { replace: true })); }} onSessionEnded={sessionEnded} />;
}

function CompanyShipments({ token, basePath, refreshKey, filters, onClearFilters, onSessionEnded, archive }: {
  token: string;
  basePath: string;
  refreshKey: number;
  filters: ShipmentFilters;
  onClearFilters: () => void;
  onSessionEnded: () => void;
  archive: ShipmentArchive;
}) {
  const [shipments, setShipments] = useState<Shipment[] | null>(null);
  const [problem, setProblem] = useState('');
  const [filterProblem, setFilterProblem] = useState('');
  const [attempt, setAttempt] = useState(0);
  const { reference, vessel, customer, arrivalDate, status, sort, atRisk, exceedsDeadline } = filters;
  const filtersActive = hasShipmentFilters(filters);

  useEffect(() => {
    let active = true;
    setProblem('');
    setFilterProblem('');
    setShipments(null);
    const appliedFilters = { reference, vessel, customer, arrivalDate, status, sort, atRisk, exceedsDeadline };
    const request = hasShipmentFilters(appliedFilters)
      ? listShipments(token, appliedFilters)
      : listShipments(token);
    request.then(found => {
      if (active) setShipments(found);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 400) {
        setFilterProblem(reason.fields.arrivalDate ?? reason.fields.status ?? reason.fields.sort ?? reason.fields.atRisk ?? reason.fields.exceedsDeadline ?? '');
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    });
    return () => { active = false; };
  }, [token, refreshKey, attempt, onSessionEnded, reference, vessel, customer, arrivalDate, status, sort, atRisk, exceedsDeadline]);

  if (problem) {
    return <div className="error-notice" role="alert">{filterProblem || problem}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button>{filterProblem && <><br /><button type="button" className="retry-button" onClick={onClearFilters}>Clear filters</button></>}</div>;
  }
  if (shipments === null) return <div className="notice">Loading shipments...</div>;
  if (shipments.length === 0) {
    return filtersActive
      ? <div className="notice">No shipments match your current filters.<br /><button type="button" className="retry-button" onClick={onClearFilters}>Clear filters</button></div>
      : <div className="notice empty-shipments">No shipments yet. Shipments registered for your company will appear here.</div>;
  }
  return <ShipmentList shipments={shipments} basePath={basePath} archive={archive} archived={status === 'ARCHIVED'} />;
}
