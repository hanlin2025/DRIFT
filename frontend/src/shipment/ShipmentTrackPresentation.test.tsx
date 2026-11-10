import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Shipment, VesselPosition, VesselTrack } from './api';
import { ShipmentTrackPresentation, type TrackPresentationData, type TrackPresentationEvents } from './ShipmentTrackPresentation';

const routeMapState = vi.hoisted(() => ({ lastProps: null as Record<string, unknown> | null }));

vi.mock('./RouteMap', () => ({
  RouteMap: (props: Record<string, unknown>) => {
    routeMapState.lastProps = props;
    return <div data-testid="route-map" />;
  },
}));

const shipment: Shipment = {
  id: 44, shipmentReference: 'FICTIONAL-44', origin: 'Shanghai, CN', destination: 'Jakarta, ID',
  transshipmentPort: 'Singapore', motherVessel: 'Fictional mother', plannedMotherArrivalAt: '2026-10-13T12:00:00Z',
  feederVessel: 'Fictional feeder', plannedFeederDepartureAt: '2026-10-14T12:00:00Z', createdAt: '2026-10-01T00:00:00Z',
  version: 1, connectionWindow: null, remainingConnectionWindow: null, risk: null, status: 'ACTIVE',
  motherVesselPosition: null, feederVesselPosition: null, arrivalComparison: null,
};

const position: VesselPosition = {
  mmsi: 'scenario-only', vesselName: 'Scenario mother', latitude: 1.2, longitude: 103.8,
  speedOverGroundKnots: 3, courseOverGroundDegrees: 90, trueHeadingDegrees: 91,
  ingestedAt: '2026-10-13T06:00:00Z', source: 'SEEDED',
};

const track: VesselTrack = {
  vessel: 'MOTHER', vesselName: 'Configured mother', mmsi: 'response-identity', windowHours: 24,
  from: '2026-10-12T08:00:00Z', to: '2026-10-13T08:00:00Z', slowdownThresholdKnots: 3,
  pointLimit: 500, truncated: false, cutoff: '2026-10-13T08:00:00Z',
  timestampSemantics: 'DRIFT_RECEIPT_TIME', context: 'RECORDED', status: 'AVAILABLE', feedStatus: 'OFFLINE',
  fallbackUsed: false, captureId: 'configured-capture', points: [
    { id: 301, latitude: 1.3, longitude: 103.9, speedOverGroundKnots: 4, courseOverGroundDegrees: 80, trueHeadingDegrees: 81, ingestedAt: '2026-10-13T07:00:00Z', source: 'AIS_STREAM' },
    { id: 302, latitude: 1.4, longitude: 104, speedOverGroundKnots: 5, courseOverGroundDegrees: 82, trueHeadingDegrees: 83, ingestedAt: '2026-10-13T08:00:00Z', source: 'AIS_STREAM' },
  ],
};

function data(overrides: Partial<TrackPresentationData> = {}): TrackPresentationData {
  return {
    shipment,
    routeKey: 'route:44:shanghai-singapore-jakarta',
    roles: {
      MOTHER: {
        sourceShipmentId: '44', captureId: 'configured-capture', mappingVersion: 'map-v1',
        identity: { name: 'Configured mother', imo: null, mmsi: 'response-identity' },
        position, history: track, state: 'available',
      },
      FEEDER: {
        sourceShipmentId: '45', captureId: null, mappingVersion: 'map-v1',
        identity: { name: 'Configured feeder', imo: null, mmsi: null },
        position: { ...position, mmsi: '', vesselName: 'Scenario feeder', latitude: 1.5, source: 'SEEDED' },
        history: { ...track, vessel: 'FEEDER', points: [] }, state: 'available',
      },
    },
    displayMode: 'scenario', selectedRole: 'MOTHER', window: 24, selectedPointId: null,
    scenarioReferenceCutoff: '2026-10-13T06:00:00Z', recordedAisCutoff: null,
    context: { scopeId: 'scope-1', sessionId: 'session-1', epoch: 'epoch-1', snapshotId: 'snapshot-1', revision: '1' },
    ...overrides,
  };
}

describe('controlled shipment track presentation', () => {
  beforeEach(() => { routeMapState.lastProps = null; });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it('renders supplied scenario data and emits control intent without reading or polling', () => {
    vi.useFakeTimers();
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    const events: TrackPresentationEvents = {
      onRefreshRequested: vi.fn(), onRoleChanged: vi.fn(), onWindowChanged: vi.fn(),
    };
    const { unmount } = render(<ShipmentTrackPresentation ownershipMode="controlled" data={data()} events={events} />);

    expect(screen.getByRole('complementary', { name: 'Mother vessel AIS' })).toHaveTextContent('Configured mother');
    expect(routeMapState.lastProps).toMatchObject({
      displayMode: 'scenario', routeIdentityKey: 'route:44:shanghai-singapore-jakarta',
      scenarioReferenceTime: '2026-10-13T06:00:00Z',
      displayPositions: [
        { role: 'MOTHER', label: 'SCENARIO', latitude: 1.2, longitude: 103.8 },
        { role: 'FEEDER', label: 'SCENARIO', latitude: 1.5 },
      ],
    });
    const motherPanel = screen.getByRole('complementary', { name: 'Mother vessel AIS' });
    expect(within(motherPanel).getByText('2026-10-13T06:00:00Z', { exact: true })).toHaveAttribute('dateTime', '2026-10-13T06:00:00Z');
    expect(motherPanel).toHaveTextContent('At simulated cutoff');
    expect(motherPanel).not.toHaveTextContent('Updated just now');
    expect(vi.getTimerCount()).toBe(0);
    expect(fetchSpy).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Feeder vessel' }));
    fireEvent.click(screen.getByRole('button', { name: '6h' }));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh positions' }));
    expect(events.onRoleChanged).toHaveBeenCalledWith('FEEDER');
    expect(events.onWindowChanged).toHaveBeenCalledWith(6);
    expect(events.onRefreshRequested).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    unmount();
  });

  it('preserves receipt timestamps and sends recorded cutoff selection to the coordinator', () => {
    const events: TrackPresentationEvents = { onPointSelected: vi.fn(), onRecordedCutoffRequested: vi.fn() };
    const view = data({ displayMode: 'recorded', selectedPointId: null, recordedAisCutoff: track.cutoff });
    render(<ShipmentTrackPresentation ownershipMode="controlled" data={view} events={events} />);

    expect(routeMapState.lastProps?.history).toBe(track);
    expect(screen.getByText('Recorded AIS · receipt-time observations')).toBeInTheDocument();
    fireEvent.click(screen.getByText('All positions'));
    const points = screen.getByRole('list', { name: 'Track points' });
    fireEvent.click(within(points).getAllByRole('button')[0]);

    expect(events.onPointSelected).toHaveBeenCalledWith(301);
    expect(events.onRecordedCutoffRequested).toHaveBeenCalledWith('2026-10-13T07:00:00Z');
    expect(screen.getByText(/Recorded AIS is a display reference\. Scenario calculations continue to use scenario positions/)).toBeInTheDocument();
  });

  it('clears denied position data while retaining the stable route identity', () => {
    const denied = data({
      displayMode: 'recorded',
      roles: { ...data().roles, MOTHER: { ...data().roles.MOTHER, state: 'denied', message: 'Denied' } },
    });
    render(<ShipmentTrackPresentation ownershipMode="controlled" data={denied} />);

    expect(routeMapState.lastProps).toMatchObject({ routeIdentityKey: denied.routeKey, history: null, displayPositions: [] });
    expect(screen.getByRole('complementary', { name: 'Mother vessel AIS' })).toHaveTextContent('Denied');
    expect(screen.queryByText('1.3000° N, 103.9000° E')).not.toBeInTheDocument();
  });
});
