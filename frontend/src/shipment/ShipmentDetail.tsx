import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { canArchiveShipment, type ShipmentArchive } from './archiveAccess';
import { getCdg201ArrivalEvidence, getMaritimeContext, getShipment, getShipmentTracking, getVesselTrack, type ArrivalComparison, type Cdg201ArrivalEvidence, type MaritimeContext, type RiskLevel, type Shipment, type ShipmentTracking, type TrackVessel, type TrackWindow, type VesselPosition, type VesselTrack } from './api';
import { Cdg201EvidencePanel } from './Cdg201EvidencePanel';
import { MaritimeContextPanel } from './MaritimeContextPanel';
import { ShipmentStatusAction } from './ShipmentStatusAction';
import { RouteMap } from './RouteMap';
import { RiskBadge } from './RiskBadge';
import { ShipmentChangeHistory } from './ShipmentChangeHistory';
import { formatWhen, PENDING_ASSIGNMENT, ShipmentForm } from './ShipmentForm';
import { TrackHistory } from './TrackHistory';
import { DEFAULT_TRACK_WINDOW } from './trackSegments';
import { VesselPanel } from './VesselPanel';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';
export const TRACKING_POLL_MS = 60_000;
const REFRESH_NOTE = 'The latest position could not be loaded. The map is still showing the last position it received.';
const DETAIL_REFRESH_NOTE = 'The latest shipment detail could not be loaded. The page is still showing the last detail it received.';
const NOT_FOUND = 'Shipment not found or has been removed.';

type Problem = { kind: 'forbidden' | 'missing' | 'failed'; message: string };

export function ShipmentDetail({ token, shipmentId, basePath, onSessionEnded, canEdit = true, archive }: {
  token: string;
  shipmentId: string;
  basePath: string;
  onSessionEnded: () => void;
  canEdit?: boolean;
  archive?: ShipmentArchive;
}) {
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [tracking, setTracking] = useState<ShipmentTracking | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [editing, setEditing] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshNote, setRefreshNote] = useState<string | null>(null);
  const [refreshCount, setRefreshCount] = useState(0);
  const requestSeq = useRef(0);
  const [trackVessel, setTrackVessel] = useState<TrackVessel>('MOTHER');
  const [trackHours, setTrackHours] = useState<TrackWindow>(DEFAULT_TRACK_WINDOW);
  const [history, setHistory] = useState<VesselTrack | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyFailed, setHistoryFailed] = useState(false);
  const [cdg201Evidence, setCdg201Evidence] = useState<Cdg201ArrivalEvidence | null>(null);
  const [maritimeContext, setMaritimeContext] = useState<MaritimeContext | null>(null);
  const [maritimeContextUnavailable, setMaritimeContextUnavailable] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const historySeq = useRef(0);

  const reloadHistory = useCallback(async () => {
    const requestId = ++historySeq.current;
    setHistoryLoading(true);
    try {
      const found = await getVesselTrack(token, shipmentId, trackVessel, trackHours);
      if (requestId !== historySeq.current) return;
      setHistory(found);
      setHistoryFailed(false);
      setSelectedId(current => current !== null && found.points.some(point => point.id === current) ? current : null);
    } catch (reason: unknown) {
      if (requestId !== historySeq.current) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 403) {
        setProblem({ kind: 'forbidden', message: reason.message });
        return;
      }
      setHistoryFailed(true);
    } finally {
      if (requestId === historySeq.current) setHistoryLoading(false);
    }
  }, [token, shipmentId, trackVessel, trackHours, onSessionEnded]);

  const reloadTracking = useCallback(async () => {
    const requestId = ++requestSeq.current;
    setRefreshing(true);
    const shipmentRequest = getShipment(token, shipmentId).then(
      found => ({ ok: true as const, found }),
      (reason: unknown) => ({ ok: false as const, reason }),
    );
    const trackingRequest = getShipmentTracking(token, shipmentId).then(
      found => ({ ok: true as const, found }),
      (reason: unknown) => ({ ok: false as const, reason }),
    );
    try {
      const [shipmentOutcome, trackingOutcome] = await Promise.all([shipmentRequest, trackingRequest]);
      if (requestId !== requestSeq.current) return;
      const reasons = [shipmentOutcome, trackingOutcome].flatMap(outcome => outcome.ok ? [] : [outcome.reason]);
      if (reasons.some(reason => reason instanceof ApiError && reason.status === 401)) {
        onSessionEnded();
        return;
      }
      const forbidden = reasons.find(reason => reason instanceof ApiError && reason.status === 403);
      if (forbidden instanceof ApiError) {
        setProblem({ kind: 'forbidden', message: forbidden.message });
        return;
      }
      if (shipmentOutcome.ok) setShipment(shipmentOutcome.found);
      if (trackingOutcome.ok) setTracking(trackingOutcome.found);
      if (!trackingOutcome.ok) setRefreshNote(REFRESH_NOTE);
      else if (!shipmentOutcome.ok) setRefreshNote(DETAIL_REFRESH_NOTE);
      else setRefreshNote(null);
    } finally {
      if (requestId === requestSeq.current) setRefreshing(false);
    }
  }, [token, shipmentId, onSessionEnded]);

  useEffect(() => {
    let active = true;
    const requestId = ++requestSeq.current;
    setShipment(null);
    setTracking(null);
    setProblem(null);
    setRefreshNote(null);
    setRefreshing(false);
    setHistory(null);
    setCdg201Evidence(null);
    setMaritimeContext(null); setMaritimeContextUnavailable(false);
    setSelectedId(null);
    getShipment(token, shipmentId).then(found => {
      if (!active || requestId !== requestSeq.current) return;
      setShipment(found);
    }).catch((reason: unknown) => {
      if (!active || requestId !== requestSeq.current) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 403) {
        setProblem({ kind: 'forbidden', message: reason.message });
        return;
      }
      setProblem(reason instanceof ApiError && reason.status === 404
        ? { kind: 'missing', message: NOT_FOUND }
        : { kind: 'failed', message: reason instanceof Error ? reason.message : UNREACHABLE });
    });
    getShipmentTracking(token, shipmentId).then(found => {
      if (!active || requestId !== requestSeq.current) return;
      setTracking(found);
    }).catch((reason: unknown) => {
      if (!active || requestId !== requestSeq.current) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 403) {
        setProblem({ kind: 'forbidden', message: reason.message });
      }
    });
    getCdg201ArrivalEvidence(token, shipmentId).then(found => {
      if (active && requestId === requestSeq.current) setCdg201Evidence(found);
    }).catch(() => {
      // Evidence replay is additive: a replay outage must not block shipment detail.
      if (active && requestId === requestSeq.current) setCdg201Evidence(null);
    });
    getMaritimeContext(token, shipmentId).then(found => {
      if (active && requestId === requestSeq.current) setMaritimeContext(found);
    }).catch(() => {
      if (active && requestId === requestSeq.current) setMaritimeContextUnavailable(true);
    });
    return () => {
      active = false;
      requestSeq.current += 1;
    };
  }, [token, shipmentId, attempt, onSessionEnded]);

  const shipmentLoaded = shipment !== null;
  useEffect(() => {
    if (problem || !shipmentLoaded) return;
    const timer = window.setInterval(() => { void reloadTracking(); }, TRACKING_POLL_MS);
    return () => window.clearInterval(timer);
  }, [problem, shipmentLoaded, reloadTracking]);

  useEffect(() => {
    if (!shipmentLoaded) return;
    setHistory(null);
    void reloadHistory();
    return () => { historySeq.current += 1; };
  }, [shipmentLoaded, reloadHistory]);

  useEffect(() => {
    if (problem || !shipmentLoaded) return;
    const timer = window.setInterval(() => { void reloadHistory(); }, TRACKING_POLL_MS);
    return () => window.clearInterval(timer);
  }, [problem, shipmentLoaded, reloadHistory]);

  return (
    <section className="shipment-detail">
      <Link className="back-link" to={basePath}><span aria-hidden="true">&#8592;</span> Back</Link>
      {problem
        ? problem.kind === 'failed'
          ? <div className="error-notice" role="alert">{problem.message}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></div>
          : <ShipmentUnavailable problem={problem} />
        : shipment === null
          ? <ShipmentSkeleton />
          : editing
            ? <ShipmentForm token={token} onSessionEnded={onSessionEnded} shipment={shipment}
                onUpdated={() => {
                  setEditing(false);
                  setAttempt(value => value + 1);
                }}
                onCancel={() => setEditing(false)}
                onReload={() => {
                  setEditing(false);
                  setAttempt(value => value + 1);
                }} />
            : <ShipmentRecord shipment={shipment} tracking={tracking} cdg201Evidence={cdg201Evidence} maritimeContext={maritimeContext} maritimeContextUnavailable={maritimeContextUnavailable} refreshing={refreshing} refreshNote={refreshNote}
                onRefresh={() => {
                  void reloadTracking();
                  void reloadHistory();
                  setRefreshCount(value => value + 1);
                }}
                canEdit={canEdit} onEdit={() => setEditing(true)} action={archive && canArchiveShipment(archive.viewer, shipment.companyId)
                  ? <ShipmentStatusAction shipment={shipment} archive={archive} />
                  : null}
                track={(
                  <TrackHistory vessel={trackVessel} hours={trackHours} history={history} loading={historyLoading}
                    failed={historyFailed} feederAssigned={Boolean(shipment.feederVessel?.trim())} selectedId={selectedId}
                    onVessel={vessel => {
                      setSelectedId(null);
                      setTrackVessel(vessel);
                    }}
                    onHours={hours => {
                      setSelectedId(null);
                      setTrackHours(hours);
                    }}
                    onSelectPoint={setSelectedId} />
                )}
                history={history} selectedId={selectedId} onSelectPoint={setSelectedId}
                changeHistory={<ShipmentChangeHistory token={token} shipmentId={shipmentId} reloadKey={attempt + refreshCount} onSessionEnded={onSessionEnded} />} />}
    </section>
  );
}

function ShipmentUnavailable({ problem }: { problem: Problem }) {
  const forbidden = problem.kind === 'forbidden';
  return (
    <div role="alert">
      <p className="eyebrow">{forbidden ? '403' : '404'}</p>
      <h2>{forbidden ? 'Access denied' : 'Shipment not found'}</h2>
      <p className="intro">{problem.message}</p>
    </div>
  );
}

const FACT_ROWS = 22;

function ShipmentSkeleton() {
  return (
    <div className="shipment-skeleton" role="status" aria-busy="true">
      <span className="sr-only">Loading shipment...</span>
      <div aria-hidden="true">
        <span className="skeleton skeleton-eyebrow" />
        <span className="skeleton skeleton-title" />
        <span className="skeleton skeleton-intro" />
        <span className="skeleton skeleton-map" />
        <div className="shipment-record route-facts">
          <dl>
            {Array.from({ length: FACT_ROWS }, (_, row) => (
              <div key={row}><dt><span className="skeleton skeleton-label" /></dt><dd><span className="skeleton skeleton-value" /></dd></div>
            ))}
          </dl>
        </div>
      </div>
    </div>
  );
}

function ShipmentRecord({ shipment, tracking, cdg201Evidence, maritimeContext, maritimeContextUnavailable, refreshing, refreshNote, onRefresh, canEdit, onEdit, action, track, history, selectedId, onSelectPoint, changeHistory }: {
  shipment: Shipment;
  tracking: ShipmentTracking | null;
  cdg201Evidence: Cdg201ArrivalEvidence | null;
  maritimeContext: MaritimeContext | null;
  maritimeContextUnavailable: boolean;
  refreshing: boolean;
  refreshNote: string | null;
  onRefresh: () => void;
  canEdit: boolean;
  onEdit: () => void;
  action: ReactNode;
  track: ReactNode;
  history: VesselTrack | null;
  selectedId: number | null;
  onSelectPoint: (id: number) => void;
  changeHistory: ReactNode;
}) {
  const remaining = remainingWindowFact(shipment);
  return (
    <>
      <p className="eyebrow">SHIPMENT</p>
      <div className="shipment-title">
        <h2>{shipment.shipmentReference}</h2>
        <div className="detail-actions">
          {canEdit && <button type="button" className="secondary-button" onClick={onEdit}>Edit shipment</button>}
          {action}
          <button type="button" className="refresh-button" onClick={onRefresh} disabled={refreshing}>{refreshing ? 'Refreshing...' : 'Refresh'}</button>
        </div>
      </div>
      <p className="intro">{recorded(shipment.origin)} to {recorded(shipment.destination)}</p>
      {refreshNote && <p className="refresh-note" role="status">{refreshNote}</p>}
      <div className="tracking-layout">
        <div className="tracking-map">
          <RouteMap shipment={shipment} tracking={tracking} history={history} selectedId={selectedId} onSelectPoint={onSelectPoint} />
          {track}
        </div>
        <VesselPanel name={shipment.motherVessel} position={tracking ? tracking.motherVessel : shipment.motherVesselPosition} />
      </div>
      <Cdg201EvidencePanel evidence={cdg201Evidence} />
      <MaritimeContextPanel context={maritimeContext} unavailable={maritimeContextUnavailable} />
      <article className="shipment-record route-facts" aria-label={`Shipment ${shipment.shipmentReference}`}>
        <dl>
          {feedFact(shipment.aisFeed)}
          <div><dt>Origin</dt><dd className={missing(shipment.origin)}>{recorded(shipment.origin)}</dd></div>
          <div><dt>Mother vessel</dt><dd className={missing(shipment.motherVessel)}><span>{recorded(shipment.motherVessel)}</span>{nameWarning(shownWarning(tracking, tracking?.motherVesselNameWarning, shipment.motherVesselNameWarning))}</dd></div>
          <div><dt>Mother vessel IMO</dt><dd className={missing(shipment.motherVesselImo)}>{recorded(shipment.motherVesselImo, 'Not available')}</dd></div>
          <div><dt>Mother vessel MMSI</dt><dd className={missing(shipment.motherVesselMmsi)}>{recorded(shipment.motherVesselMmsi, 'Not available')}</dd></div>
          <div><dt>Mother vessel operator</dt><dd className={missing(shipment.motherVesselOperator)}>{recorded(shipment.motherVesselOperator, 'Not available')}</dd></div>
          <div><dt>Mother vessel position</dt><dd>{formatLive(tracking ? tracking.motherVessel : shipment.motherVesselPosition)}</dd></div>
          {operationFact('Mother vessel operation', shipment.motherOperationalState)}
          <div>
            <dt>Planned arrival</dt>
            <dd><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></dd>
            {shipment.arrivalComparison && <ArrivalComparisonFacts comparison={shipment.arrivalComparison} />}
          </div>
          <div><dt>Transshipment port</dt><dd className={missing(shipment.transshipmentPort)}>{recorded(shipment.transshipmentPort)}</dd></div>
          <div><dt>Feeder vessel</dt><dd className={missing(shipment.feederVessel)}><span className={missing(shipment.feederVessel)}>{recorded(shipment.feederVessel, PENDING_ASSIGNMENT)}</span>{nameWarning(shownWarning(tracking, tracking?.feederVesselNameWarning, shipment.feederVesselNameWarning))}</dd></div>
          <div><dt>Feeder vessel IMO</dt><dd className={missing(shipment.feederVesselImo)}>{recorded(shipment.feederVesselImo, 'Not available')}</dd></div>
          <div><dt>Feeder vessel MMSI</dt><dd className={missing(shipment.feederVesselMmsi)}>{recorded(shipment.feederVesselMmsi, 'Not available')}</dd></div>
          <div><dt>Feeder vessel operator</dt><dd className={missing(shipment.feederVesselOperator)}>{recorded(shipment.feederVesselOperator, 'Not available')}</dd></div>
          <div><dt>Feeder vessel position</dt><dd>{formatLive(tracking ? tracking.feederVessel : shipment.feederVesselPosition)}</dd></div>
          {operationFact('Feeder vessel operation', shipment.feederOperationalState)}
          <div><dt>Planned departure</dt>{shipment.plannedFeederDepartureAt
            ? <dd><time dateTime={shipment.plannedFeederDepartureAt}>{formatWhen(shipment.plannedFeederDepartureAt)}</time></dd>
            : <dd className="is-missing">{PENDING_ASSIGNMENT}</dd>}</div>
          <div><dt>Destination</dt><dd className={missing(shipment.destination)}>{recorded(shipment.destination)}</dd></div>
          <div>
            <dt>Connection window</dt>
            <dd>{shipment.connectionWindow ? shipment.connectionWindow.duration : 'No planned connection window'}</dd>
          </div>
          <div>
            <dt>Remaining connection window</dt>
            <dd className={remaining.missing ? 'is-missing' : undefined}>
              {remaining.text}
              {shipment.remainingConnectionWindow && shipment.arrivalComparison?.lastReceivedAt
                ? <dl className="arrival-comparison">{reportTime(shipment.arrivalComparison.lastReceivedAt)}</dl>
                : null}
            </dd>
          </div>
          <div><dt>Risk</dt>{shipment.risk
            ? <dd><RiskBadge risk={shipment.risk} /> {shipment.risk.explanation}</dd>
            : <dd className="is-missing"><RiskBadge risk={null} /></dd>}</div>
          <div><dt>Registered</dt><dd><time dateTime={shipment.createdAt}>{formatWhen(shipment.createdAt)}</time></dd></div>
        </dl>
      </article>
      {changeHistory}
    </>
  );
}

function ArrivalComparisonFacts({ comparison }: { comparison: ArrivalComparison }) {
  if (comparison.unavailableReason) {
    return (
      <dl className="arrival-comparison">
        <div><dt>Estimated arrival</dt><dd>{comparison.unavailableReason.message}</dd></div>
      </dl>
    );
  }
  return (
    <dl className="arrival-comparison">
      <div><dt>Estimated arrival</dt><dd><time dateTime={comparison.estimatedArrivalAt}>{formatWhen(comparison.estimatedArrivalAt)}</time></dd></div>
      <div><dt>Comparison</dt><dd>{comparison.difference}</dd></div>
      <div><dt>Limitation</dt><dd>{comparison.limitation}</dd></div>
      <div><dt>Speed used</dt><dd>{comparison.speedOverGroundKnots} knots</dd></div>
      {reportTime(comparison.lastReceivedAt)}
      {comparison.possiblyOutdated && <div><dt>Position freshness</dt><dd>Possibly outdated</dd></div>}
    </dl>
  );
}

function formatLive(position: VesselPosition | null) {
  if (!position) return 'No live AIS position';
  const name = position.vesselName?.trim() || position.mmsi;
  const speed = position.speedOverGroundKnots == null ? '' : ` · ${position.speedOverGroundKnots} kn`;
  const seeded = position.source === 'SEEDED' ? ' · Seeded position' : '';
  return `${name} · ${position.latitude}, ${position.longitude}${speed}${seeded}`;
}

function reportTime(at: string | null | undefined) {
  if (!at) return null;
  return <div><dt>Last received</dt><dd><time dateTime={at}>{formatWhen(at)}</time></dd></div>;
}

function feedFact(feed: Shipment['aisFeed']) {
  if (!feed) return null;
  const label = feed.status === 'LIVE' ? 'Live' : feed.status === 'DELAYED' ? 'Delayed' : 'Offline';
  return (
    <div>
      <dt>AIS feed</dt>
      <dd>
        {label}
        <dl className="arrival-comparison">
          {feed.lastReceivedAt
            ? reportTime(feed.lastReceivedAt)
            : <div><dt>Last received</dt><dd className="is-missing">No AIS message has been received</dd></div>}
          {feed.status === 'OFFLINE' ? <div><dt>Analysis</dt><dd>The analysis is based on the last data received.</dd></div> : null}
        </dl>
      </dd>
    </div>
  );
}

function operationFact(label: string, state: Shipment['motherOperationalState']) {
  if (!state) return null;
  return (
    <div>
      <dt>{label}</dt>
      <dd>
        {state.description}
        {state.ingestedAt ? <dl className="arrival-comparison">{reportTime(state.ingestedAt)}</dl> : null}
      </dd>
    </div>
  );
}

function remainingWindowFact(shipment: Shipment): { text: string; missing: boolean } {
  const remaining = shipment.remainingConnectionWindow;
  if (remaining?.status === 'AVAILABLE') {
    return { text: remaining.duration, missing: false };
  }
  if (remaining?.status === 'MISSED') {
    return { text: `The connection is expected to be missed by ${remaining.missedBy}.`, missing: false };
  }
  if (!shipment.plannedFeederDepartureAt?.trim()) {
    return { text: 'No remaining connection window', missing: true };
  }
  const comparison = shipment.arrivalComparison;
  if (comparison?.unavailableReason) {
    return { text: `The planned connection window is being used. ${comparison.unavailableReason.message}`, missing: false };
  }
  if (comparison?.possiblyOutdated) {
    return { text: "The planned connection window is being used because the mother vessel's position is possibly outdated.", missing: false };
  }
  return { text: 'No remaining connection window', missing: true };
}

function recorded(value: string | null | undefined, fallback = 'Not recorded') {
  const text = value?.trim();
  return text || fallback;
}

function missing(value: string | null | undefined) {
  return value?.trim() ? undefined : 'is-missing';
}

function shownWarning(tracking: ShipmentTracking | null, fromTracking: string | null | undefined, fromDetail: string | null | undefined) {
  return tracking ? fromTracking ?? null : fromDetail;
}

function nameWarning(warning: string | null | undefined) {
  const text = warning?.trim();
  return text ? <span className="vessel-name-warning">{text}</span> : null;
}
