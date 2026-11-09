# CDG-287 controlled track presentation

`ShipmentTrackPresentation` renders the existing shipment route map, AIS track history and vessel panel from one caller-supplied snapshot. It does not call the API, start timers, or substitute live positions when the supplied snapshot is unavailable. Its default `ownershipMode` is `controlled`.

## Exported interface

Import from `frontend/src/shipment/ShipmentTrackPresentation`:

```ts
export type ShipmentTrackPresentationProps = {
  ownershipMode?: 'live' | 'controlled';
  data: TrackPresentationData;
  events?: TrackPresentationEvents;
};

export type TrackPresentationData = {
  shipment: Shipment;
  routeKey: string;
  roles: Record<'MOTHER' | 'FEEDER', TrackRolePresentation>;
  displayMode: 'live' | 'scenario' | 'recorded';
  selectedRole: 'MOTHER' | 'FEEDER';
  window: 6 | 24 | 72;
  selectedPointId: number | null;
  scenarioReferenceCutoff: string | null;
  recordedAisCutoff: string | null;
  context: TrackAuthorizationContext | null;
};

export type TrackRolePresentation = {
  sourceShipmentId: string | null;
  captureId: string | null;
  mappingVersion: string | null;
  identity: { name: string | null; imo: string | null; mmsi: string | null };
  position: VesselPosition | null;
  history: VesselTrack | null;
  state: 'loading' | 'available' | 'unavailable' | 'denied' | 'error';
  message?: string | null;
};

export type TrackAuthorizationContext = {
  scopeId: string;
  sessionId: string;
  epoch: string;
  snapshotId: string;
  revision: string;
};

export type TrackPresentationEvents = {
  onRefreshRequested?: () => void;
  onRoleChanged?: (role: 'MOTHER' | 'FEEDER') => void;
  onWindowChanged?: (window: 6 | 24 | 72) => void;
  onPointSelected?: (pointId: number) => void;
  onRecordedCutoffRequested?: (cutoff: string) => void;
  onDisplayModeChanged?: (mode: 'live' | 'scenario' | 'recorded') => void;
};
```

The component also exports the named types `TrackDisplayMode`, `TrackPresentationOwnership`, and `TrackReadState`. `Shipment`, `VesselPosition`, and `VesselTrack` are the existing API types from `frontend/src/shipment/api`.

## CDG-290 usage

The coordinator should keep `routeKey` stable while only a snapshot, revision, selected point, cutoff, or role changes. Change it when the route identity changes. Include the current scope, session, epoch, snapshot and revision in `context`; discard delayed responses whose context no longer matches before passing data to the view.

```tsx
import {
  ShipmentTrackPresentation,
  type TrackPresentationData,
  type TrackPresentationEvents,
} from './shipment/ShipmentTrackPresentation';

const presentation: TrackPresentationData = {
  shipment,
  routeKey: `shipment:${shipment.id}:route:${routeVersion}`,
  roles: {
    MOTHER: {
      sourceShipmentId: bindings.mother.sourceShipmentId,
      captureId: bindings.mother.captureId,
      mappingVersion: bindings.mother.mappingVersion,
      identity: { name: mother.name, imo: mother.imo, mmsi: mother.mmsi },
      position: mother.position,
      history: mother.history,
      state: mother.state,
    },
    FEEDER: {
      sourceShipmentId: bindings.feeder.sourceShipmentId,
      captureId: bindings.feeder.captureId,
      mappingVersion: bindings.feeder.mappingVersion,
      identity: { name: feeder.name, imo: feeder.imo, mmsi: feeder.mmsi },
      position: feeder.position,
      history: feeder.history,
      state: feeder.state,
    },
  },
  displayMode: stage ? 'scenario' : 'recorded',
  selectedRole,
  window: trackWindow,
  selectedPointId,
  scenarioReferenceCutoff: stage?.referenceCutoff ?? null,
  recordedAisCutoff,
  context: { scopeId, sessionId, epoch, snapshotId, revision },
};

const events: TrackPresentationEvents = {
  onRefreshRequested: refreshSnapshot,
  onRoleChanged: setSelectedRole,
  onWindowChanged: setTrackWindow,
  onPointSelected: setSelectedPointId,
  onRecordedCutoffRequested: requestRecordedCutoff,
  onDisplayModeChanged: setDisplayMode,
};

<ShipmentTrackPresentation
  ownershipMode="controlled"
  data={presentation}
  events={events}
/>
```

In controlled mode the coordinator owns reads, timing, and stale-response rejection. A denied role should be passed with `state: 'denied'`, `position: null`, and `history: null`; if the whole shipment scope is denied, clear both roles and replace the current authorization context. The map clears protected track markers when its context changes. A role's source shipment, capture ID and mapping version are display provenance; backend authorization and capture validation remain authoritative.

`scenarioReferenceCutoff` is kept separate from the backend's receipt-time `recordedAisCutoff`. The component displays original observation receipt timestamps and does not rebase them. It never fills gaps with inferred positions. Last-known fallback is shown only from `history.lastKnownPosition` when the supplied track says `fallbackUsed`. Basemap failure has its own status; coordinates, timestamps, history and shipment facts remain visible outside the map canvas.

`ownershipMode="live"` can be used by a caller that supplies a live snapshot; the component still has no network reads. The ordinary Shipment Details page remains the live data owner and preserves its existing behavior.

## Mapping and evidence boundary

Mother and feeder are separate AIS display candidates. A supplied role binding must be reviewed and authorized by the coordinator and backend. The component does not establish that the vessels share a booking, cargo or transshipment. CDG-287 supplies no approved authentic vessel/capture mapping, so its offline rehearsal uses only local `SEEDED` positions with the AIS provider disabled. It does not establish authentic AIS replay or offline basemap availability.
