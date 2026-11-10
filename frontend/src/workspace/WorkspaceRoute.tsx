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
import { WorkspaceFrame, type WorkspaceSection } from './WorkspaceFrame';

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

function workspaceSection(page: 'portfolio' | 'import' | 'register', shipmentId: string | undefined): WorkspaceSection {
  switch (page) {
    case 'import':
      return 'import';
    case 'register':
      return 'register';
    case 'portfolio':
      return shipmentId ? 'shipment' : 'shipments';
    default: {
      const exhaustive: never = page;
      return exhaustive;
    }
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
    document.title = page === 'import'
      ? 'Import shipments | DRIFT'
      : page === 'register'
        ? 'Register a shipment | DRIFT'
        : shipmentId
          ? 'Shipment | DRIFT'
          : 'Shipments | DRIFT';
  }, [page, shipmentId]);

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
    <WorkspaceFrame account={account} onSignOut={onSignOut} section={workspaceSection(page, shipmentId)}>
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
    </WorkspaceFrame>
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
