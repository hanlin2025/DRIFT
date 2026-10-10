import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { getShipment, getShipmentTracking, type RiskLevel, type Shipment, type ShipmentTracking, type VesselPosition } from './api';
import { RouteMap } from './RouteMap';
import { formatWhen, PENDING_ASSIGNMENT, ShipmentForm } from './ShipmentForm';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';
export const TRACKING_POLL_MS = 60_000;
const REFRESH_NOTE = 'The latest position could not be loaded. The map is still showing the last position it received.';
const NOT_FOUND = 'Shipment not found or has been removed.';

type Problem = { kind: 'forbidden' | 'missing' | 'failed'; message: string };

export function ShipmentDetail({ token, shipmentId, basePath, onSessionEnded }: {
  token: string;
  shipmentId: string;
  basePath: string;
  onSessionEnded: () => void;
}) {
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [tracking, setTracking] = useState<ShipmentTracking | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [editing, setEditing] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshNote, setRefreshNote] = useState<string | null>(null);
  const requestSeq = useRef(0);

  const reloadTracking = useCallback(async () => {
    const requestId = ++requestSeq.current;
    setRefreshing(true);
    try {
      const found = await getShipmentTracking(token, shipmentId);
      if (requestId !== requestSeq.current) return;
      setTracking(found);
      setRefreshNote(null);
    } catch (reason: unknown) {
      if (requestId !== requestSeq.current) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 403) {
        setProblem({ kind: 'forbidden', message: reason.message });
        return;
      }
      setRefreshNote(REFRESH_NOTE);
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
    getShipment(token, shipmentId).then(found => {
      if (active) setShipment(found);
    }).catch((reason: unknown) => {
      if (!active) return;
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
    return () => {
      active = false;
      requestSeq.current += 1;
    };
  }, [token, shipmentId, attempt, onSessionEnded]);

  useEffect(() => {
    if (problem || shipment === null) return;
    const timer = window.setInterval(() => { void reloadTracking(); }, TRACKING_POLL_MS);
    return () => window.clearInterval(timer);
  }, [problem, shipment, reloadTracking]);

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
            : <ShipmentRecord shipment={shipment} tracking={tracking} refreshing={refreshing} refreshNote={refreshNote} onRefresh={() => void reloadTracking()} onEdit={() => setEditing(true)} />}
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

const FACT_ROWS = 12;

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

function ShipmentRecord({ shipment, tracking, refreshing, refreshNote, onRefresh, onEdit }: {
  shipment: Shipment;
  tracking: ShipmentTracking | null;
  refreshing: boolean;
  refreshNote: string | null;
  onRefresh: () => void;
  onEdit: () => void;
}) {
  return (
    <>
      <p className="eyebrow">SHIPMENT</p>
      <div className="shipment-title">
        <h2>{shipment.shipmentReference}</h2>
        <div className="detail-actions">
          <button type="button" className="secondary-button" onClick={onEdit}>Edit shipment</button>
          <button type="button" className="refresh-button" onClick={onRefresh} disabled={refreshing}>{refreshing ? 'Refreshing...' : 'Refresh'}</button>
        </div>
      </div>
      <p className="intro">{recorded(shipment.origin)} to {recorded(shipment.destination)}</p>
      {refreshNote && <p className="refresh-note" role="status">{refreshNote}</p>}
      <RouteMap shipment={shipment} tracking={tracking} />
      <article className="shipment-record route-facts" aria-label={`Shipment ${shipment.shipmentReference}`}>
        <dl>
          <div><dt>Origin</dt><dd className={missing(shipment.origin)}>{recorded(shipment.origin)}</dd></div>
          <div><dt>Mother vessel</dt><dd className={missing(shipment.motherVessel)}>{recorded(shipment.motherVessel)}</dd></div>
          <div><dt>Mother vessel position</dt><dd>{formatLive(tracking ? tracking.motherVessel : shipment.motherVesselPosition)}</dd></div>
          <div><dt>Planned arrival</dt><dd><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></dd></div>
          <div><dt>Transshipment port</dt><dd className={missing(shipment.transshipmentPort)}>{recorded(shipment.transshipmentPort)}</dd></div>
          <div><dt>Feeder vessel</dt><dd className={missing(shipment.feederVessel)}>{recorded(shipment.feederVessel, PENDING_ASSIGNMENT)}</dd></div>
          <div><dt>Feeder vessel position</dt><dd>{formatLive(tracking ? tracking.feederVessel : shipment.feederVesselPosition)}</dd></div>
          <div><dt>Planned departure</dt>{shipment.plannedFeederDepartureAt
            ? <dd><time dateTime={shipment.plannedFeederDepartureAt}>{formatWhen(shipment.plannedFeederDepartureAt)}</time></dd>
            : <dd className="is-missing">{PENDING_ASSIGNMENT}</dd>}</div>
          <div><dt>Destination</dt><dd className={missing(shipment.destination)}>{recorded(shipment.destination)}</dd></div>
          <div>
            <dt>Connection window</dt>
            <dd>{shipment.connectionWindow ? shipment.connectionWindow.duration : 'No planned connection window'}</dd>
          </div>
          <div><dt>Risk</dt>{shipment.risk
            ? <dd><span className={`risk-badge risk-${shipment.risk.level.toLowerCase()}`}>{RISK_LABELS[shipment.risk.level]}</span> {shipment.risk.explanation}</dd>
            : <dd className="is-missing">{PENDING_ASSIGNMENT}</dd>}</div>
          <div><dt>Registered</dt><dd><time dateTime={shipment.createdAt}>{formatWhen(shipment.createdAt)}</time></dd></div>
        </dl>
      </article>
    </>
  );
}

const RISK_LABELS: Record<RiskLevel, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' };

function formatLive(position: VesselPosition | null) {
  if (!position) return 'No live AIS position';
  const name = position.vesselName?.trim() || position.mmsi;
  const speed = position.speedOverGroundKnots == null ? '' : ` · ${position.speedOverGroundKnots} kn`;
  return `${name} · ${position.latitude}, ${position.longitude}${speed}`;
}

function recorded(value: string | null, fallback = 'Not recorded') {
  const text = value?.trim();
  return text || fallback;
}

function missing(value: string | null) {
  return value?.trim() ? undefined : 'is-missing';
}
