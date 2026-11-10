import { RouteMap } from './RouteMap';
import { TrackHistory } from './TrackHistory';
import { VesselPanel } from './VesselPanel';
import type { TrackDisplayMode, TrackPresentationData, TrackPresentationEvents, TrackPresentationOwnership } from './trackPresentationContract';

export type { TrackAuthorizationContext, TrackDisplayMode, TrackPresentationData, TrackPresentationEvents,
  TrackPresentationOwnership, TrackReadState, TrackRolePresentation } from './trackPresentationContract';

export type ShipmentTrackPresentationProps = {
  ownershipMode?: TrackPresentationOwnership;
  data: TrackPresentationData;
  events?: TrackPresentationEvents;
};

/**
 * Read-only map and track view for an external snapshot coordinator.
 * It consumes one atomic data object and emits intent callbacks; it owns no network reads or timers.
 */
export function ShipmentTrackPresentation({ ownershipMode = 'controlled', data, events = {} }: ShipmentTrackPresentationProps) {
  const role = data.selectedRole;
  const selected = data.roles[role];
  const denied = selected.state === 'denied';
  const history = denied ? null : selected.history;
  const position = denied ? null : selected.position;
  const point = data.selectedPointId == null
    ? null
    : history?.points.find(item => item.id === data.selectedPointId) ?? null;
  const fallback = history?.fallbackUsed ? history.lastKnownPosition ?? null : null;
  const shown = data.displayMode === 'recorded' ? point ?? fallback ?? history?.points.at(-1) ?? null : null;
  const allPositions = (['MOTHER', 'FEEDER'] as const).flatMap(vessel => {
    const candidate = data.roles[vessel];
    if (candidate.state === 'denied' || candidate.state === 'unavailable' || !candidate.position) return [];
    return [{
      role: vessel,
      name: candidate.identity.name ?? (vessel === 'MOTHER' ? data.shipment.motherVessel : data.shipment.feederVessel ?? 'Feeder vessel'),
      latitude: candidate.position.latitude,
      longitude: candidate.position.longitude,
      ingestedAt: candidate.position.ingestedAt,
      speedOverGroundKnots: candidate.position.speedOverGroundKnots,
      label: data.displayMode === 'scenario' ? 'SCENARIO' as const
        : candidate.position.source === 'SEEDED' ? 'SEEDED' as const : 'LIVE' as const,
    }];
  });
  const displayPositions = data.displayMode === 'recorded'
    ? shown ? [{
      role,
      name: selected.identity.name ?? history?.vesselName ?? (role === 'MOTHER' ? data.shipment.motherVessel : data.shipment.feederVessel ?? 'Feeder vessel'),
      latitude: shown.latitude,
      longitude: shown.longitude,
      ingestedAt: shown.ingestedAt,
      speedOverGroundKnots: shown.speedOverGroundKnots,
      label: fallback && shown === fallback ? 'LAST KNOWN' as const
        : history?.context === 'SEEDED' || shown.source === 'SEEDED' ? 'SEEDED' as const : 'RECORDED' as const,
    }] : []
    : allPositions;
  const stateMessage = selected.message ?? (selected.state === 'unavailable'
    ? 'Positions are unavailable for this role and snapshot.'
    : selected.state === 'denied' ? 'Track history is unavailable to this account.'
      : selected.state === 'error' ? 'Track history could not be loaded.' : null);
  const contextKey = data.context
    ? [data.context.scopeId, data.context.sessionId, data.context.epoch, data.context.snapshotId, data.context.revision].join(':')
    : 'controlled-context-unavailable';

  const selectPoint = (pointId: number) => {
    events.onPointSelected?.(pointId);
    if (data.displayMode === 'recorded') {
      const found = history?.points.find(item => item.id === pointId);
      if (found) events.onRecordedCutoffRequested?.(found.ingestedAt);
    }
  };
  const step = (direction: 'previous' | 'next') => {
    const points = history?.points ?? [];
    if (points.length === 0) return;
    const current = points.findIndex(item => item.id === data.selectedPointId);
    const index = direction === 'next' ? (current < 0 ? 0 : current + 1) : (current < 0 ? points.length - 1 : current - 1);
    const point = points[index];
    if (!point) return;
    events.onPointSelected?.(point.id);
    events.onRecordedCutoffRequested?.(point.ingestedAt);
  };

  return (
    <section className="controlled-track-presentation" aria-label="Shipment position presentation">
      <div className="tracking-layout">
        <div className="tracking-map">
          <RouteMap shipment={data.shipment} history={history} selectedId={data.selectedPointId}
            onSelectPoint={selectPoint} displayPositions={displayPositions} displayMode={data.displayMode}
            contextKey={contextKey} routeIdentityKey={data.routeKey} scenarioReferenceTime={data.scenarioReferenceCutoff} />
          <TrackHistory vessel={role} hours={data.window} history={history}
            loading={selected.state === 'loading'} failed={selected.state === 'error'}
            feederAssigned={Boolean(data.shipment.feederVessel?.trim())} selectedId={data.selectedPointId}
            onVessel={events.onRoleChanged ?? (() => {})} onHours={events.onWindowChanged ?? (() => {})}
            onSelectPoint={selectPoint} mode={data.displayMode}
            displayModes={['live', 'scenario', 'recorded']}
            cutoff={data.recordedAisCutoff} playbackPoints={history?.points ?? []} readState={selected.state}
            statusMessage={stateMessage} scenarioReferenceCutoff={data.scenarioReferenceCutoff}
            sourceShipmentId={selected.sourceShipmentId} mappingVersion={selected.mappingVersion}
            recordedCaption="Recorded AIS is a display reference. Scenario calculations continue to use scenario positions."
            onDisplayModeChange={events.onDisplayModeChanged} onCutoffRequest={events.onRecordedCutoffRequested}
            onPlaybackStep={step} onRefreshRequested={events.onRefreshRequested} />
        </div>
        <VesselPanel name={selected.identity.name ?? (role === 'MOTHER' ? data.shipment.motherVessel : data.shipment.feederVessel)}
          position={data.displayMode === 'recorded' || denied ? null : position}
          view={{
            role, mode: data.displayMode, ownershipMode, history, selectedPointId: data.selectedPointId,
            referenceTime: data.displayMode === 'recorded' ? data.recordedAisCutoff : data.scenarioReferenceCutoff,
            state: selected.state, message: stateMessage, identityName: selected.identity.name,
          }} />
      </div>
    </section>
  );
}
