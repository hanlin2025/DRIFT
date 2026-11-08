import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { canArchiveShipment, type ShipmentArchive } from './archiveAccess';
import { generateMaritimeAiAssessment, getCdg201ArrivalEvidence, getMaritimeContext, getShipment, getShipmentTracking, getVesselTrack, listMaritimeAiAssessments, type ArrivalComparison, type Cdg201ArrivalEvidence, type MaritimeAiAssessment, type MaritimeContext, type PortConditionComparison, type RecommendationDecision, type RecommendationImpact, type RiskLevel, type Shipment, type ShipmentTracking, type TrackPoint, type TrackVessel, type TrackWindow, type VesselPosition, type VesselTrack } from './api';
import { RecommendationDecisionPanel } from './RecommendationDecisionPanel';
import { Cdg201EvidencePanel } from './Cdg201EvidencePanel';
import { MaritimeContextPanel } from './MaritimeContextPanel';
import { MaritimeAiAssessmentPanel } from './MaritimeAiAssessmentPanel';
import { ShipmentStatusAction } from './ShipmentStatusAction';
import { RouteMap } from './RouteMap';
import { RiskBadge } from './RiskBadge';
import { ShipmentChangeHistory } from './ShipmentChangeHistory';
import { formatWhen, PENDING_ASSIGNMENT, ShipmentForm } from './ShipmentForm';
import { DelayComparisonFact } from './DelayComparisonFact';
import { TransshipmentArrivalDeadlineFact } from './TransshipmentArrivalDeadline';
import { TrackHistory } from './TrackHistory';
import { DEFAULT_TRACK_WINDOW } from './trackSegments';
import { VesselPanel } from './VesselPanel';
import type { TrackDisplayMode, TrackReadState } from './trackPresentationContract';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';
export const TRACKING_POLL_MS = 60_000;
const REFRESH_NOTE = 'The latest position could not be loaded. The map is still showing the last position it received.';
const DETAIL_REFRESH_NOTE = 'The latest shipment detail could not be loaded. The page is still showing the last detail it received.';
const NOT_FOUND = 'Shipment not found or has been removed.';

type Problem = { kind: 'forbidden' | 'missing' | 'failed'; message: string };

export type ShipmentTrackBinding = {
  sourceShipmentId: string;
  captureId?: string | null;
  mappingVersion?: string | null;
};

export type ShipmentTrackBindings = Partial<Record<TrackVessel, ShipmentTrackBinding>>;

export function ShipmentDetail({ token, shipmentId, basePath, onSessionEnded, canEdit = true, viewerCompanyId = null, archive, trackBindings,
  controlledScenarioEnabled = false, onEnterControlledScenario }: {
  token: string;
  shipmentId: string;
  basePath: string;
  onSessionEnded: () => void;
  canEdit?: boolean;
  viewerCompanyId?: number | null;
  archive?: ShipmentArchive;
  /** Optional reviewed per-role source shipment/capture mapping. The backend still authorizes and validates each read. */
  trackBindings?: ShipmentTrackBindings;
  /** Visibility only; scenario access is still decided by the authenticated backend. */
  controlledScenarioEnabled?: boolean;
  onEnterControlledScenario?: (authorizedAnchor: Shipment) => void;
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
  const [trackMode, setTrackMode] = useState<Extract<TrackDisplayMode, 'live' | 'recorded'>>('live');
  const [recordedCutoff, setRecordedCutoff] = useState<string | null>(null);
  const [playbackPoints, setPlaybackPoints] = useState<TrackPoint[]>([]);
  const [playbackState, setPlaybackState] = useState<TrackReadState>('loading');
  const [playbackMessage, setPlaybackMessage] = useState<string | null>(null);
  const [playbackContext, setPlaybackContext] = useState<VesselTrack['context']>(undefined);
  const [playbackVesselName, setPlaybackVesselName] = useState<string | null>(null);
  const [history, setHistory] = useState<VesselTrack | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyFailed, setHistoryFailed] = useState(false);
  const [cdg201Evidence, setCdg201Evidence] = useState<Cdg201ArrivalEvidence | null>(null);
  const [maritimeContext, setMaritimeContext] = useState<MaritimeContext | null>(null);
  const [maritimeContextUnavailable, setMaritimeContextUnavailable] = useState(false);
  const [maritimeAiAssessments, setMaritimeAiAssessments] = useState<MaritimeAiAssessment[]>([]);
  const [maritimeAiUnavailable, setMaritimeAiUnavailable] = useState(false);
  const [maritimeAiGenerating, setMaritimeAiGenerating] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [portCapture, setPortCapture] = useState<{ nonce: number; port: string } | null>(null);
  const historySeq = useRef(0);
  const decisionSeq = useRef(0);
  const refreshNonce = useRef(0);
  const contextKey = `${shipmentId}:${viewerCompanyId ?? ''}:${token}`;
  const [context, setContext] = useState(contextKey);
  if (context !== contextKey) {
    setContext(contextKey);
    setShipment(null);
    setTracking(null);
    setProblem(null);
    setRefreshNote(null);
    setHistory(null);
    setSelectedId(null);
    setTrackVessel('MOTHER');
    setTrackHours(DEFAULT_TRACK_WINDOW);
    setTrackMode('live');
    setRecordedCutoff(null);
    setPlaybackPoints([]);
    setPlaybackState('loading');
    setPlaybackMessage(null);
    setPlaybackContext(undefined);
    setPlaybackVesselName(null);
  }

  const reloadHistory = useCallback(async (requestedCutoff?: string | null) => {
    const requestId = ++historySeq.current;
    const binding = trackBindings?.[trackVessel];
    const cutoff = trackMode === 'recorded' ? requestedCutoff ?? recordedCutoff ?? new Date().toISOString() : undefined;
    const sourceShipmentId = binding?.sourceShipmentId ?? shipmentId;
    setHistoryLoading(true);
    setPlaybackState(trackMode === 'recorded' ? 'loading' : 'available');
    setPlaybackMessage(null);
    try {
      const options = {
        ...(cutoff ? { cutoff } : {}),
        ...(trackMode === 'recorded' && binding?.captureId ? { captureId: binding.captureId } : {}),
      };
      const found = Object.keys(options).length > 0
        ? await getVesselTrack(token, sourceShipmentId, trackVessel, trackHours, options)
        : await getVesselTrack(token, sourceShipmentId, trackVessel, trackHours);
      if (requestId !== historySeq.current) return;
      if (found.vessel !== trackVessel) {
        setHistory(null);
        setHistoryFailed(true);
        setPlaybackState('unavailable');
        setPlaybackMessage('Track identity did not match the selected vessel.');
        return;
      }
      if (trackMode === 'recorded' && found.context !== 'RECORDED' && found.context !== 'SEEDED') {
        setHistory(null);
        setHistoryFailed(true);
        setPlaybackState('unavailable');
        setPlaybackMessage('No recorded or seeded positions are available for this vessel and receipt-time cutoff.');
        return;
      }
      setHistory(found);
      setHistoryFailed(false);
      setSelectedId(current => current !== null && found.points.some(point => point.id === current) ? current : null);
      if (trackMode === 'recorded') {
        setPlaybackPoints(current => current.length > 0 ? current : found.points);
        setPlaybackContext(found.context);
        setPlaybackVesselName(found.vesselName);
        setPlaybackState(found.status === 'UNAVAILABLE' ? 'unavailable' : 'available');
        setPlaybackMessage(found.status === 'UNAVAILABLE' ? 'Track data is unavailable at this cutoff.' : null);
      } else setPlaybackState('available');
    } catch (reason: unknown) {
      if (requestId !== historySeq.current) return;
      if (reason instanceof ApiError && reason.status === 401) {
        setHistory(null);
        setTracking(null);
        setPlaybackPoints([]);
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 403) {
        setHistory(null);
        setTracking(null);
        setPlaybackPoints([]);
        setProblem({ kind: 'forbidden', message: reason.message });
        return;
      }
      if (reason instanceof ApiError && reason.status === 404) {
        setHistory(null);
        setTracking(null);
        setPlaybackPoints([]);
        setProblem({ kind: 'missing', message: NOT_FOUND });
        return;
      }
      setHistory(null);
      setHistoryFailed(true);
      setPlaybackState(trackMode === 'recorded' ? 'unavailable' : 'error');
      setPlaybackMessage(reason instanceof ApiError && reason.status >= 400 ? reason.message : null);
    } finally {
      if (requestId === historySeq.current) setHistoryLoading(false);
    }
  }, [token, shipmentId, trackVessel, trackHours, trackMode, recordedCutoff, trackBindings, onSessionEnded]);

  const reloadTracking = useCallback(async (mode: 'background' | 'explicit' = 'background') => {
    const requestId = ++requestSeq.current;
    setRefreshing(true);
    const shipmentRequest = getShipment(token, shipmentId).then(
      found => ({ ok: true as const, found }),
      (reason: unknown) => ({ ok: false as const, reason }),
    );
    const trackingRequest = trackMode === 'live'
      ? getShipmentTracking(token, shipmentId).then(
        found => ({ ok: true as const, found }),
        (reason: unknown) => ({ ok: false as const, reason }),
      )
      : Promise.resolve({ ok: true as const, found: null });
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
        setShipment(null);
        setTracking(null);
        setProblem({ kind: 'forbidden', message: forbidden.message });
        return;
      }
      if (!shipmentOutcome.ok && shipmentOutcome.reason instanceof ApiError && shipmentOutcome.reason.status === 404) {
        setShipment(null);
        setTracking(null);
        setProblem({ kind: 'missing', message: NOT_FOUND });
        return;
      }
      if (shipmentOutcome.ok) {
        setShipment(shipmentOutcome.found);
        if (mode === 'explicit') {
          setPortCapture({ nonce: ++refreshNonce.current, port: shipmentOutcome.found.transshipmentPort });
        }
      }
      if (trackingOutcome.ok && trackingOutcome.found) setTracking(trackingOutcome.found);
      if (!trackingOutcome.ok) setRefreshNote(REFRESH_NOTE);
      else if (!shipmentOutcome.ok) setRefreshNote(DETAIL_REFRESH_NOTE);
      else setRefreshNote(null);
    } finally {
      if (requestId === requestSeq.current) setRefreshing(false);
    }
  }, [token, shipmentId, trackMode, onSessionEnded]);

  const changeTrackMode = useCallback((mode: TrackDisplayMode) => {
    if (mode !== 'live' && mode !== 'recorded') return;
    setSelectedId(null);
    setPlaybackMessage(null);
    if (mode === 'recorded') {
      setTrackMode('recorded');
      setRecordedCutoff(history?.to ?? new Date().toISOString());
      setPlaybackPoints([]);
      setPlaybackContext(undefined);
      setPlaybackVesselName(null);
      setPlaybackState('loading');
    } else {
      setTrackMode('live');
      setRecordedCutoff(null);
      setPlaybackPoints([]);
      setPlaybackContext(undefined);
      setPlaybackVesselName(null);
      setPlaybackState('available');
    }
  }, [history?.to]);

  const selectTrackPoint = useCallback((id: number) => {
    const point = trackMode === 'recorded'
      ? playbackPoints.find(item => item.id === id)
      : history?.points.find(item => item.id === id);
    setSelectedId(id);
    if (trackMode === 'recorded' && point && point.ingestedAt !== recordedCutoff) {
      setRecordedCutoff(point.ingestedAt);
    }
  }, [trackMode, playbackPoints, history, recordedCutoff]);

  const stepPlayback = useCallback((direction: 'previous' | 'next') => {
    if (playbackPoints.length === 0) return;
    const current = playbackPoints.findIndex(point => point.id === selectedId);
    const next = direction === 'next'
      ? current < 0 ? 0 : current + 1
      : current < 0 ? playbackPoints.length - 1 : current - 1;
    const point = playbackPoints[next];
    if (!point) return;
    setSelectedId(point.id);
    if (point.ingestedAt !== recordedCutoff) setRecordedCutoff(point.ingestedAt);
  }, [playbackPoints, selectedId, recordedCutoff]);

  const changeTrackVessel = useCallback((vessel: TrackVessel) => {
    setPlaybackPoints([]);
    setPlaybackContext(undefined);
    setPlaybackVesselName(null);
    setPlaybackMessage(null);
    setPlaybackState(trackMode === 'recorded' ? 'loading' : 'available');
    setSelectedId(null);
    setTrackVessel(vessel);
  }, [trackMode]);

  const changeTrackHours = useCallback((hours: TrackWindow) => {
    setPlaybackPoints([]);
    setPlaybackContext(undefined);
    setPlaybackVesselName(null);
    setPlaybackMessage(null);
    setPlaybackState(trackMode === 'recorded' ? 'loading' : 'available');
    setSelectedId(null);
    setTrackHours(hours);
  }, [trackMode]);

  const generateMaritimeAi = useCallback(async () => {
    setMaritimeAiGenerating(true);
    try {
      const generated = await generateMaritimeAiAssessment(token, shipmentId);
      setMaritimeAiAssessments(current => [generated, ...current]);
      setMaritimeAiUnavailable(false);
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) onSessionEnded();
      else setMaritimeAiUnavailable(true);
    } finally {
      setMaritimeAiGenerating(false);
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
    setTrackMode('live');
    setRecordedCutoff(null);
    setPlaybackPoints([]);
    setPlaybackState('loading');
    setPlaybackMessage(null);
    setPlaybackContext(undefined);
    setPlaybackVesselName(null);
    setCdg201Evidence(null);
    setMaritimeContext(null); setMaritimeContextUnavailable(false);
    setMaritimeAiAssessments([]); setMaritimeAiUnavailable(false);
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
      if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) {
        setShipment(null);
        setTracking(null);
        setProblem(reason.status === 403
          ? { kind: 'forbidden', message: reason.message }
          : { kind: 'missing', message: NOT_FOUND });
        return;
      }
      setProblem({ kind: 'failed', message: reason instanceof Error ? reason.message : UNREACHABLE });
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
        setShipment(null);
        setTracking(null);
        setProblem({ kind: 'forbidden', message: reason.message });
      }
    });
    // Evidence replay is additive. A rejected call, or a call that does not return a
    // response, must not unmount the shipment the viewer already opened.
    void Promise.resolve()
      .then(() => getCdg201ArrivalEvidence(token, shipmentId))
      .then(found => {
        if (active && requestId === requestSeq.current) setCdg201Evidence(found ?? null);
      })
      .catch(() => {
        if (active && requestId === requestSeq.current) setCdg201Evidence(null);
      });
    // Maritime Context is likewise additive. Keep shipment detail available if its
    // optional client throws synchronously, returns no promise, or rejects; shipment
    // detail and its recommendation remain available.
    void Promise.resolve()
      .then(() => getMaritimeContext(token, shipmentId))
      .then(found => {
        if (!active || requestId !== requestSeq.current) return;
        if (found) setMaritimeContext(found);
        else setMaritimeContextUnavailable(true);
      })
      .catch(() => {
        if (active && requestId === requestSeq.current) setMaritimeContextUnavailable(true);
      });
    // AI assessments are independently additive and must not prevent the evidence panel or
    // shipment detail from rendering when an optional client throws or returns a non-Promise.
    void Promise.resolve()
      .then(() => listMaritimeAiAssessments(token, shipmentId))
      .then(found => {
        if (!active || requestId !== requestSeq.current) return;
        if (Array.isArray(found)) setMaritimeAiAssessments(found);
        else setMaritimeAiUnavailable(true);
      })
      .catch(() => {
        if (active && requestId === requestSeq.current) setMaritimeAiUnavailable(true);
      });
    return () => {
      active = false;
      requestSeq.current += 1;
    };
  }, [token, shipmentId, viewerCompanyId, attempt, onSessionEnded]);

  const shipmentLoaded = shipment !== null;
  useEffect(() => {
    if (problem || !shipmentLoaded || trackMode !== 'live') return;
    const timer = window.setInterval(() => { void reloadTracking('background'); }, TRACKING_POLL_MS);
    return () => window.clearInterval(timer);
  }, [problem, shipmentLoaded, trackMode, reloadTracking]);

  useEffect(() => {
    if (!shipmentLoaded) return;
    setHistory(null);
    void reloadHistory();
    return () => { historySeq.current += 1; };
  }, [shipmentLoaded, reloadHistory]);

  useEffect(() => {
    if (problem || !shipmentLoaded || trackMode !== 'live') return;
    const timer = window.setInterval(() => { void reloadHistory(); }, TRACKING_POLL_MS);
    return () => window.clearInterval(timer);
  }, [problem, shipmentLoaded, trackMode, reloadHistory]);

  return (
    <section className="shipment-detail">
      <Link className="back-link" to={basePath}><span aria-hidden="true">&#8592;</span> Back</Link>
      {controlledScenarioEnabled && viewerCompanyId !== null && shipment?.companyId === viewerCompanyId
        && !problem && !editing && onEnterControlledScenario && (
        <aside className="scenario-entry-callout" aria-label="Synthetic controlled scenario">
          <div>
            <p className="eyebrow">OPTIONAL SYNTHETIC DEMONSTRATION</p>
            <p>Open a controlled, read-only three-stage scenario for this independently authorized shipment.</p>
          </div>
          <button type="button" className="secondary-button" onClick={() => onEnterControlledScenario(shipment)}>
            Enter controlled scenario
          </button>
        </aside>
      )}
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
            : <ShipmentRecord shipment={shipment} tracking={tracking} cdg201Evidence={cdg201Evidence} maritimeContext={maritimeContext} maritimeContextUnavailable={maritimeContextUnavailable} maritimeAiAssessments={maritimeAiAssessments} maritimeAiUnavailable={maritimeAiUnavailable} maritimeAiGenerating={maritimeAiGenerating} onGenerateMaritimeAi={generateMaritimeAi} refreshing={refreshing} refreshNote={refreshNote}
                onRefresh={() => {
                  void reloadTracking('explicit');
                  void reloadHistory();
                  setRefreshCount(value => value + 1);
                }}
                onDecision={recorded => {
                  const seq = ++decisionSeq.current;
                  setShipment(current => current ? { ...current, recommendationDecision: recorded } : current);
                  void getShipment(token, shipmentId).then(fresh => {
                    if (seq !== decisionSeq.current) return;
                  setShipment(current => current && current.id === fresh.id ? fresh : current);
                  }).catch((reason: unknown) => {
                    if (reason instanceof ApiError && reason.status === 401) onSessionEnded();
                  });
                }}
                canEdit={canEdit} onEdit={() => setEditing(true)} action={archive && canArchiveShipment(archive.viewer, shipment.companyId)
                  ? <ShipmentStatusAction shipment={shipment} archive={archive} />
                  : null}
                track={(
                  <TrackHistory vessel={trackVessel} hours={trackHours} history={history} loading={historyLoading}
                    failed={historyFailed} feederAssigned={Boolean(shipment.feederVessel?.trim())} selectedId={selectedId}
                    onVessel={changeTrackVessel}
                    onHours={changeTrackHours}
                    onSelectPoint={selectTrackPoint}
                    mode={trackMode} cutoff={recordedCutoff} playbackPoints={playbackPoints}
                    readState={trackMode === 'recorded' ? playbackState : historyFailed ? 'error' : 'available'}
                    statusMessage={playbackMessage} onDisplayModeChange={changeTrackMode}
                    sourceShipmentId={trackBindings?.[trackVessel]?.sourceShipmentId ?? shipmentId}
                    mappingVersion={trackBindings?.[trackVessel]?.mappingVersion}
                    onCutoffRequest={cutoff => {
                      setSelectedId(null);
                      setRecordedCutoff(cutoff);
                      setPlaybackState('loading');
                    }} onPlaybackStep={stepPlayback} />
                )}
                history={history} selectedId={selectedId} onSelectPoint={selectTrackPoint}
                trackMode={trackMode} trackVessel={trackVessel} recordedCutoff={recordedCutoff}
                playbackPoints={playbackPoints} playbackState={playbackState} playbackMessage={playbackMessage}
                playbackContext={playbackContext} playbackVesselName={playbackVesselName}
                mapContextKey={contextKey}
                viewerCompanyId={viewerCompanyId} token={token} onSessionEnded={onSessionEnded}
                portCapture={portCapture} onExplicitRefresh={() => { void reloadTracking('explicit'); }}
                onShipment={setShipment}
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

const FACT_ROWS = 25;

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

function ShipmentRecord({ shipment, tracking, cdg201Evidence, maritimeContext, maritimeContextUnavailable, maritimeAiAssessments, maritimeAiUnavailable, maritimeAiGenerating, onGenerateMaritimeAi, refreshing, refreshNote, onRefresh, onDecision, canEdit, onEdit, action, track, history, selectedId, onSelectPoint, trackMode, trackVessel, recordedCutoff, playbackPoints, playbackState, playbackMessage, playbackContext, playbackVesselName, mapContextKey, viewerCompanyId, token, onSessionEnded, portCapture, onExplicitRefresh, onShipment, changeHistory }: {
  shipment: Shipment;
  tracking: ShipmentTracking | null;
  cdg201Evidence: Cdg201ArrivalEvidence | null;
  maritimeContext: MaritimeContext | null;
  maritimeContextUnavailable: boolean;
  maritimeAiAssessments: MaritimeAiAssessment[];
  maritimeAiUnavailable: boolean;
  maritimeAiGenerating: boolean;
  onGenerateMaritimeAi: () => void;
  refreshing: boolean;
  refreshNote: string | null;
  onRefresh: () => void;
  onDecision: (decision: RecommendationDecision) => void;
  canEdit: boolean;
  onEdit: () => void;
  action: ReactNode;
  track: ReactNode;
  history: VesselTrack | null;
  selectedId: number | null;
  onSelectPoint: (id: number) => void;
  trackMode: 'live' | 'recorded';
  trackVessel: TrackVessel;
  recordedCutoff: string | null;
  playbackPoints: TrackPoint[];
  playbackState: TrackReadState;
  playbackMessage: string | null;
  playbackContext: VesselTrack['context'];
  playbackVesselName: string | null;
  mapContextKey: string;
  viewerCompanyId: number | null;
  token: string;
  onSessionEnded: () => void;
  portCapture: { nonce: number; port: string } | null;
  onExplicitRefresh: () => void;
  onShipment: (shipment: Shipment) => void;
  changeHistory: ReactNode;
}) {
  const remaining = remainingWindowFact(shipment);
  const selectedPlaybackPoint = selectedId == null
    ? null
    : playbackPoints.find(point => point.id === selectedId) ?? history?.points.find(point => point.id === selectedId) ?? null;
  const lastKnownPlaybackPoint = trackMode === 'recorded' && history?.points.length === 0 && history.fallbackUsed
    ? history.lastKnownPosition ?? null
    : null;
  const shownPlaybackPoint = selectedPlaybackPoint ?? lastKnownPlaybackPoint;
  const playbackName = playbackVesselName?.trim() || history?.vesselName?.trim()
    || (trackVessel === 'MOTHER' ? shipment.motherVessel : shipment.feederVessel?.trim() || 'Feeder vessel');
  const displayPositions = trackMode === 'recorded' && shownPlaybackPoint ? [{
    role: trackVessel,
    name: playbackName,
    latitude: shownPlaybackPoint.latitude,
    longitude: shownPlaybackPoint.longitude,
    ingestedAt: shownPlaybackPoint.ingestedAt,
    speedOverGroundKnots: shownPlaybackPoint.speedOverGroundKnots,
    label: lastKnownPlaybackPoint && shownPlaybackPoint === lastKnownPlaybackPoint ? 'LAST KNOWN' as const
      : playbackContext === 'SEEDED' || history?.context === 'SEEDED' ? 'SEEDED' as const : 'RECORDED' as const,
  }] : undefined;
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
          <RouteMap shipment={shipment} tracking={trackMode === 'recorded' ? null : tracking} history={history} selectedId={selectedId} onSelectPoint={onSelectPoint}
            displayMode={trackMode} displayPositions={displayPositions} contextKey={mapContextKey} />
          {track}
        </div>
        <VesselPanel name={trackVessel === 'MOTHER' ? shipment.motherVessel : shipment.feederVessel}
          position={trackMode === 'recorded' ? null : tracking ? tracking.motherVessel : shipment.motherVesselPosition}
          view={trackMode === 'recorded' ? {
            role: trackVessel, mode: 'recorded', history, selectedPointId: selectedId,
            referenceTime: recordedCutoff, state: playbackState, message: playbackMessage,
            identityName: playbackVesselName,
          } : undefined} />
      </div>
      <section className="shipment-assessment-summary" aria-label="Backend-calculated shipment assessment">
        <h3>Shipment and backend assessment</h3>
        <p>Shipment schedule, risk, recommendation and impact below are calculated by DRIFT. The AI interpretation is shown separately and does not approve or apply a change.</p>
      </section>
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
          {portConditionFact(shipment.portConditionComparison)}
          <div>
            <dt>Planned arrival</dt>
            <dd><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></dd>
            {shipment.arrivalComparison && <ArrivalComparisonFacts comparison={shipment.arrivalComparison} />}
          </div>
          <div><dt>Transshipment port</dt><dd className={missing(shipment.transshipmentPort)}>{recorded(shipment.transshipmentPort)}</dd></div>
          <TransshipmentArrivalDeadlineFact shipment={shipment} viewerCompanyId={viewerCompanyId} token={token}
            onSessionEnded={onSessionEnded} onShipment={onShipment} portCapture={portCapture} onExplicitRefresh={onExplicitRefresh} />
          <DelayComparisonFact shipment={shipment} />
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
          {recommendationFact(shipment.recommendation)}
          <div><dt>Registered</dt><dd><time dateTime={shipment.createdAt}>{formatWhen(shipment.createdAt)}</time></dd></div>
        </dl>
      </article>
<RecommendationDecisionPanel token={token} shipmentId={shipment.id} recommendation={shipment.recommendation}
        decision={shipment.recommendationDecision} history={shipment.recommendationDecisionHistory}
        archived={shipment.status === 'ARCHIVED'}
        onSessionEnded={onSessionEnded} onRecorded={onDecision} />
      <Cdg201EvidencePanel evidence={cdg201Evidence} />
      <MaritimeContextPanel context={maritimeContext} unavailable={maritimeContextUnavailable} />
      <MaritimeAiAssessmentPanel assessments={maritimeAiAssessments} unavailable={maritimeAiUnavailable} generating={maritimeAiGenerating} onGenerate={onGenerateMaritimeAi} />
      {changeHistory}
    </>
  );
}

const IMPACT_OUTCOMES: Record<'AFFECTS_CONSTRAINTS' | 'NO_PREDICTED_IMPACT', string> = {
  AFFECTS_CONSTRAINTS: "Affects the importer's constraints",
  NO_PREDICTED_IMPACT: "Does not affect the importer's constraints",
};

const PORT_OUTCOME_LABELS: Record<NonNullable<PortConditionComparison['outcome']>, string> = {
  MATCHES_NORMAL: 'Matches own reports',
  SLOWER_THAN_OWN_REPORTS: 'Slower than own reports',
};

function portConditionFact(comparison: Shipment['portConditionComparison']) {
  if (!comparison) return null;
  const heading = comparison.outcome ? PORT_OUTCOME_LABELS[comparison.outcome] : comparison.message;
  const port = comparison.knownPort ?? comparison.transshipmentPort;
  const vessel = comparison.vesselName?.trim();
  return (
    <div>
      <dt>Port conditions</dt>
      <dd>
        {heading}
        <dl className="arrival-comparison">
          {comparison.outcome && <div><dt>Comparison</dt><dd>{comparison.message}</dd></div>}
          <div><dt>Vessel</dt><dd>{vessel ? `Mother · ${vessel}` : 'Mother'}</dd></div>
          {port ? <div><dt>Port</dt><dd>{port}</dd></div> : null}
          {comparison.speedOverGroundKnots != null
            ? <div><dt>Current speed</dt><dd>{comparison.speedOverGroundKnots} knots</dd></div>
            : null}
          {comparison.slowestOwnSpeedKnots != null
            ? <div><dt>Slowest own reports</dt><dd>{comparison.slowestOwnSpeedKnots} knots</dd></div>
            : null}
          {comparison.outcome
            ? <div><dt>Earlier reports</dt><dd>{comparison.earlierReportCount}</dd></div>
            : null}
          {comparison.latitude != null && comparison.longitude != null
            ? <div><dt>Position used</dt><dd>{comparison.latitude}, {comparison.longitude}</dd></div>
            : null}
          {reportTime(comparison.ingestedAt)}
          {comparison.source === 'SEEDED' ? <div><dt>Source</dt><dd>Seeded position</dd></div> : null}
        </dl>
      </dd>
    </div>
  );
}

function recommendationFact(recommendation: Shipment['recommendation']) {
  if (!recommendation) return null;
  const risk = recommendation.riskLevel && recommendation.riskExplanation
    ? { level: recommendation.riskLevel, explanation: recommendation.riskExplanation }
    : null;
  return (
    <div>
      <dt>Recommendation</dt>
      <dd>
        {recommendation.available ? recommendation.response.label : recommendation.unavailable}
        <dl className="arrival-comparison">
          {recommendation.available && <div><dt>Response</dt><dd>{recommendation.response.wording}</dd></div>}
          {risk && (
            <div>
              <dt>Connection risk</dt>
              <dd><RiskBadge risk={risk} /> {risk.explanation}</dd>
            </div>
          )}
          <div><dt>Impact</dt><dd>{impactOutcome(recommendation.impact)}</dd></div>
        </dl>
      </dd>
    </div>
  );
}

function impactOutcome(impact: RecommendationImpact) {
  if (impact.outcome === 'UNAVAILABLE') return impact.detail;
  return IMPACT_OUTCOMES[impact.outcome];
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
