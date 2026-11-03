import { useEffect, useMemo, useState } from 'react';
import type { Session } from '../session/session';
import type { Shipment, TrackVessel } from './api';
import { ScenarioControls } from './ScenarioControls';
import { ScenarioFacts } from './ScenarioFacts';
import { decodeScenarioSnapshot } from './scenarioDomain';
import { adaptScenarioSnapshotToTrackPresentation } from './scenarioPresentation';
import type { ScenarioPresentationContext, ScenarioPresentationView } from './scenarioPresentation';
import { ShipmentTrackPresentation } from './ShipmentTrackPresentation';
import { DEFAULT_TRACK_WINDOW } from './trackSegments';
import { useScenarioSnapshot } from './useScenarioSnapshot';

export type ControlledScenarioDetailProps = Readonly<{
  viewer: Session;
  anchor: Shipment;
  authContextGeneration: string;
  onExit: () => void;
  onNavigateToShipment: (shipmentId: number) => void;
  onSessionEnded: () => void;
}>;

/** The only mounted owner of the scenario session. It performs no ordinary shipment, tracking, history, or AI reads. */
export function ControlledScenarioDetail({ viewer, anchor, authContextGeneration, onExit, onNavigateToShipment, onSessionEnded }: ControlledScenarioDetailProps) {
  const controller = useScenarioSnapshot({ viewer, anchorShipmentId: anchor.id });
  const [selectedRole, setSelectedRole] = useState<TrackVessel>('MOTHER');
  const [unavailableView, setUnavailableView] = useState<string | null>(null);
  const [hiddenResetEpoch, setHiddenResetEpoch] = useState<string | null>(null);
  const session = controller.session;
  const binding = controller.binding;
  const snapshot = session?.snapshot ?? null;

  useEffect(() => {
    if (controller.error?.status === 401) onSessionEnded();
  }, [controller.error, onSessionEnded]);

  const selected = useMemo(() => {
    if (!session?.snapshot || !binding) return null;
    return decodeScenarioSnapshot(session, {
      sessionId: session.sessionId,
      epochId: session.epochId,
      snapshotId: session.snapshot.snapshotId,
      revision: session.revision,
      shipmentId: anchor.id,
      scenarioShipmentKey: binding.scenarioShipmentKey,
    }) ?? null;
  }, [session, binding, anchor.id]);

  const presentationContext: ScenarioPresentationContext | null = session && snapshot && binding
    ? {
      authorization: controller.error?.status === 403 ? 'denied' : 'authorized',
      viewerId: viewer.id,
      companyId: viewer.company?.id ?? null,
      authContextGeneration,
      routeShipmentId: anchor.id,
      scenarioShipmentKey: binding.scenarioShipmentKey,
      sessionId: session.sessionId,
      epochId: session.epochId,
      snapshotId: snapshot.snapshotId,
      revision: session.revision,
      lifecycleState: session.state,
    }
    : null;

  const hiddenAfterReset = hiddenResetEpoch !== null
    && session?.epochId === hiddenResetEpoch
    && session.snapshot !== null;
  const presentation = selected && presentationContext && !hiddenAfterReset
    ? adaptScenarioSnapshotToTrackPresentation(selected, anchor, presentationContext, {
      displayMode: 'scenario', selectedRole, window: DEFAULT_TRACK_WINDOW,
      selectedPointId: null, recordedAisCutoff: null,
    } satisfies ScenarioPresentationView)
    : null;

  const protectedController = useMemo(() => ({
    ...controller,
    session: hiddenAfterReset ? null : controller.session,
    snapshot: hiddenAfterReset ? null : controller.snapshot,
    binding: hiddenAfterReset ? null : controller.binding,
    start: async () => {
      setHiddenResetEpoch(null);
      setUnavailableView(null);
      await controller.start();
    },
    reset: async () => {
      setHiddenResetEpoch(controller.session?.epochId ?? null);
      setUnavailableView(null);
      await controller.reset();
    },
    refresh: async () => {
      setUnavailableView(null);
      await controller.refresh();
      setHiddenResetEpoch(null);
    },
    retryCommand: async () => {
      setUnavailableView(null);
      await controller.retryCommand();
    },
  }), [controller, hiddenAfterReset]);

  const navigationDisabled = controller.busy || controller.pendingAction !== null;
  const showUnavailableSnapshot = Boolean(session?.snapshot && !selected);
  const hasActiveScenario = Boolean(presentation && selected);

  function changeDisplayMode(mode: 'live' | 'scenario' | 'recorded') {
    if (mode === 'live') {
      if (navigationDisabled) {
        setUnavailableView('Resolve the pending scenario command before exiting to live shipment details.');
        return;
      }
      onExit();
    } else if (mode === 'recorded') {
      setUnavailableView('Recorded AIS is unavailable in this synthetic scenario. No history request was made.');
    } else {
      setUnavailableView(null);
    }
  }

  return (
    <section className="shipment-detail controlled-scenario-detail" aria-label="Controlled synthetic scenario">
      <div className="scenario-detail-heading">
        <div>
          <p className="eyebrow">CONTROLLED SCENARIO · SYNTHETIC DEMONSTRATION</p>
          <h2>{anchor.shipmentReference}</h2>
          <p className="intro">Scenario view for authorized shipment {anchor.shipmentReference}. Fictional scenario facts are display-only.</p>
        </div>
        <button type="button" className="secondary-button" onClick={onExit} disabled={navigationDisabled}>
          Exit to live Shipment Details
        </button>
      </div>

      {session?.shipments.length === 2 && binding && (
        <div className="scenario-binding-switch" role="group" aria-label="Scenario shipment selection">
          {session.shipments.map(candidate => (
            <button key={candidate.scenarioShipmentKey} type="button" aria-pressed={candidate.shipmentId === anchor.id}
              disabled={navigationDisabled || candidate.shipmentId === anchor.id}
              onClick={() => onNavigateToShipment(candidate.shipmentId)}>
              Scenario {candidate.scenarioShipmentKey} · {candidate.shipmentReference}
            </button>
          ))}
          <p>Each selection is independently authorized before its scenario data is read.</p>
        </div>
      )}

      <ScenarioControls controller={protectedController} />
      {unavailableView && <p className="scenario-view-notice" role="status">{unavailableView}</p>}
      {controller.status === 'loading' && !session && <p className="notice" role="status">Verifying controlled scenario access…</p>}
      {controller.error?.status === 403 && <p className="error-notice" role="alert">Scenario access is denied. Protected scenario positions and facts have been cleared.</p>}
      {showUnavailableSnapshot && <p className="error-notice" role="alert">The returned scenario snapshot could not be validated. No scenario facts or positions are displayed.</p>}
      {session?.state === 'UNSTARTED' && !controller.busy && !controller.error && (
        <p className="notice" role="status">UNSTARTED — no scenario snapshot, positions, risk, or calculated outputs are displayed.</p>
      )}
      {session?.state !== 'UNSTARTED' && !snapshot && !controller.error && !controller.busy && (
        <p className="notice" role="status">No confirmed scenario snapshot is available.</p>
      )}
      {hiddenAfterReset && <p className="notice" role="status">The previous snapshot is cleared from view while Reset is being resolved.</p>}

      {presentation && selected && (
        <>
          {!hasActiveScenario ? null : <ShipmentTrackPresentation ownershipMode="controlled" data={presentation} events={{
            onRefreshRequested: () => { void controller.refresh(); },
            onRoleChanged: role => { setSelectedRole(role); setUnavailableView(null); },
            onWindowChanged: () => setUnavailableView('Historical windows are unavailable for a single scenario snapshot. No history request was made.'),
            onPointSelected: () => setUnavailableView('Historical points are unavailable for a single scenario snapshot.'),
            onRecordedCutoffRequested: () => setUnavailableView('Recorded AIS is unavailable in this synthetic scenario. No history request was made.'),
            onDisplayModeChanged: changeDisplayMode,
          }} />}
          <ScenarioFacts snapshot={selected} />
        </>
      )}
    </section>
  );
}
