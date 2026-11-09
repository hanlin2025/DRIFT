import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { getShipment, type Shipment } from './api';
import { ControlledScenarioDetail } from './ControlledScenarioDetail';
import { ShipmentDetail } from './ShipmentDetail';
import { ApiError } from '../signup/api';
import { shipmentDetailPath, type Session } from '../session/session';

let authGenerationSequence = 0;

export type ShipmentDetailEntryProps = Readonly<{
  viewer: Session;
  shipmentId: string;
  basePath: string;
  onSessionEnded: () => void;
  canEdit?: boolean;
  archive?: Parameters<typeof ShipmentDetail>[0]['archive'];
  controlledScenarioEnabled: boolean;
}>;

function hasControlledScenarioIntent(state: unknown): boolean {
  return typeof state === 'object' && state !== null && 'controlledScenario' in state
    && state.controlledScenario === true;
}

/** Selects exactly one detail owner. The ordinary detail page is unmounted before the scenario coordinator starts. */
export function ShipmentDetailEntry({ viewer, shipmentId, basePath, onSessionEnded, canEdit = true, archive,
  controlledScenarioEnabled }: ShipmentDetailEntryProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const authContextGeneration = useMemo(() => `shipment-detail-auth-${++authGenerationSequence}`,
    [viewer.token, viewer.id, viewer.company?.id, viewer.role]);
  const ownerKey = JSON.stringify([viewer.id, viewer.company?.id ?? null, authContextGeneration, shipmentId]);
  const controlledIntent = controlledScenarioEnabled && hasControlledScenarioIntent(location.state);
  const [entered, setEntered] = useState<{ ownerKey: string; anchor: Shipment } | null>(null);
  const authorizedAnchor = entered?.ownerKey === ownerKey ? entered.anchor : null;

  useEffect(() => {
    if (entered && entered.ownerKey !== ownerKey) setEntered(null);
  }, [entered, ownerKey]);

  function exitControlledScenario() {
    setEntered(null);
    navigate(location.pathname, { replace: true, state: null });
  }

  function navigateToShipment(targetShipmentId: number) {
    navigate(shipmentDetailPath(viewer.role, String(targetShipmentId)), { state: { controlledScenario: true } });
  }

  if (controlledIntent) {
    return <AuthorizedScenarioAnchor key={ownerKey} viewer={viewer} shipmentId={shipmentId}
      authContextGeneration={authContextGeneration} onExit={exitControlledScenario}
      onNavigateToShipment={navigateToShipment} onSessionEnded={onSessionEnded} />;
  }

  if (authorizedAnchor) {
    return <ControlledScenarioDetail key={ownerKey} viewer={viewer} anchor={authorizedAnchor}
      authContextGeneration={authContextGeneration} onExit={exitControlledScenario}
      onNavigateToShipment={navigateToShipment} onSessionEnded={onSessionEnded} />;
  }

  return <ShipmentDetail token={viewer.token} shipmentId={shipmentId} basePath={basePath}
    onSessionEnded={onSessionEnded} canEdit={canEdit} viewerCompanyId={viewer.company?.id ?? null}
    archive={archive} controlledScenarioEnabled={controlledScenarioEnabled}
    onEnterControlledScenario={anchor => setEntered({ ownerKey, anchor })} />;
}

type AnchorView =
  | { ownerKey: string; status: 'loading' }
  | { ownerKey: string; status: 'ready'; anchor: Shipment }
  | { ownerKey: string; status: 'denied' | 'error'; message: string };

function AuthorizedScenarioAnchor({ viewer, shipmentId, authContextGeneration, onExit, onNavigateToShipment,
  onSessionEnded }: {
  viewer: Session;
  shipmentId: string;
  authContextGeneration: string;
  onExit: () => void;
  onNavigateToShipment: (shipmentId: number) => void;
  onSessionEnded: () => void;
}) {
  const [attempt, setAttempt] = useState(0);
  const ownerKey = JSON.stringify([viewer.id, viewer.company?.id ?? null, authContextGeneration, shipmentId]);
  const [view, setView] = useState<AnchorView | null>(null);
  const current = view?.ownerKey === ownerKey ? view : { ownerKey, status: 'loading' as const };

  useEffect(() => {
    let active = true;
    const routeId = Number(shipmentId);
    setView({ ownerKey, status: 'loading' });
    if (!Number.isSafeInteger(routeId) || routeId <= 0 || !viewer.company?.id) {
      setView({ ownerKey, status: 'denied', message: 'An independently authorized company shipment is required for this scenario.' });
      return () => { active = false; };
    }

    getShipment(viewer.token, shipmentId).then(anchor => {
      if (!active) return;
      if (anchor.id !== routeId || anchor.companyId !== viewer.company?.id) {
        setView({ ownerKey, status: 'denied', message: 'This shipment is not authorized for the selected viewer and company.' });
        return;
      }
      setView({ ownerKey, status: 'ready', anchor });
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) {
        setView({ ownerKey, status: 'denied', message: 'Shipment access could not be verified. No scenario session or protected data was loaded.' });
        return;
      }
      setView({ ownerKey, status: 'error', message: reason instanceof Error
        ? reason.message : 'Shipment authorization could not be verified. Try again.' });
    });

    return () => { active = false; };
  }, [viewer.token, viewer.id, viewer.company?.id, shipmentId, ownerKey, attempt, onSessionEnded]);

  if (current.status === 'loading') {
    return <section className="shipment-detail scenario-anchor-gate" aria-busy="true" aria-label="Verify scenario shipment access">
      <p className="notice" role="status">Verifying shipment authorization before loading controlled scenario data…</p>
    </section>;
  }
  if (current.status !== 'ready') {
    return <section className="shipment-detail scenario-anchor-gate" aria-label="Controlled scenario access">
      <p className="eyebrow">CONTROLLED SCENARIO</p>
      <h2>Scenario shipment unavailable</h2>
      <p className={current.status === 'denied' ? 'error-notice' : 'notice'} role={current.status === 'denied' ? 'alert' : 'status'}>{current.message}</p>
      {current.status === 'error' && <button type="button" className="secondary-button" onClick={() => setAttempt(value => value + 1)}>Retry authorization check</button>}
      <button type="button" className="secondary-button" onClick={onExit}>Return to live Shipment Details</button>
    </section>;
  }

  return <ControlledScenarioDetail key={ownerKey} viewer={viewer} anchor={current.anchor}
    authContextGeneration={authContextGeneration} onExit={onExit}
    onNavigateToShipment={onNavigateToShipment} onSessionEnded={onSessionEnded} />;
}
