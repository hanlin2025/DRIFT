import type { Shipment, TrackVessel, TrackWindow, VesselPosition, VesselTrack } from './api';

export type TrackPresentationOwnership = 'live' | 'controlled';
export type TrackDisplayMode = 'live' | 'scenario' | 'recorded';
export type TrackReadState = 'loading' | 'available' | 'unavailable' | 'denied' | 'error';

export type TrackAuthorizationContext = {
  scopeId: string;
  sessionId: string;
  epoch: string;
  snapshotId: string;
  revision: string;
};

export type TrackRolePresentation = {
  sourceShipmentId: string | null;
  captureId: string | null;
  mappingVersion: string | null;
  identity: { name: string | null; imo: string | null; mmsi: string | null };
  position: VesselPosition | null;
  history: VesselTrack | null;
  state: TrackReadState;
  message?: string | null;
};

export type TrackPresentationData = {
  shipment: Shipment;
  /** Stable route key, unchanged by snapshot, cutoff, selection, and refresh updates. */
  routeKey: string;
  roles: Record<TrackVessel, TrackRolePresentation>;
  displayMode: TrackDisplayMode;
  selectedRole: TrackVessel;
  window: TrackWindow;
  selectedPointId: number | null;
  scenarioReferenceCutoff: string | null;
  recordedAisCutoff: string | null;
  context: TrackAuthorizationContext | null;
};

export type TrackPresentationEvents = {
  onRefreshRequested?: () => void;
  onRoleChanged?: (role: TrackVessel) => void;
  onWindowChanged?: (window: TrackWindow) => void;
  onPointSelected?: (pointId: number) => void;
  onRecordedCutoffRequested?: (cutoff: string) => void;
  onDisplayModeChanged?: (mode: TrackDisplayMode) => void;
};
