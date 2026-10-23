import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RouteMap } from './RouteMap';
import type { Shipment, ShipmentTracking, VesselTrack } from './api';

const mapCalls = vi.hoisted(() => ({
  jumpTo: vi.fn(),
  fitBounds: vi.fn(),
  addLayer: vi.fn(),
  setData: vi.fn(),
  setFilter: vi.fn(),
  layerHandlers: new Map<string, (event: unknown) => void>(),
}));

vi.mock('maplibre-gl', () => ({
  Map: class Map {
    on(event: string, layerOrCallback: string | (() => void), handler?: (event: unknown) => void) {
      if (typeof layerOrCallback === 'function') layerOrCallback();
      else if (handler) mapCalls.layerHandlers.set(`${event}:${layerOrCallback}`, handler);
    }
    setProjection() {}
    addSource() {}
    addLayer(layer: unknown) { mapCalls.addLayer(layer); }
    getSource(id: string) { return { setData: (data: unknown) => mapCalls.setData(id, data) }; }
    setFilter(id: string, filter: unknown) { mapCalls.setFilter(id, filter); }
    getCanvas() { return { style: {} }; }
    loaded() { return false; }
    remove() {}
    addControl() {}
    jumpTo(options: unknown) { mapCalls.jumpTo(options); }
    fitBounds(bounds: unknown, options: unknown) { mapCalls.fitBounds(bounds, options); }
  },
  NavigationControl: class NavigationControl {},
  Marker: class Marker {
    setLngLat() { return this; }
    addTo() { return this; }
    remove() {}
  },
  LngLatBounds: class LngLatBounds {
    extend() { return this; }
  },
}));

const shipment: Shipment = {
  id: 2,
  shipmentReference: 'HBL-NEWER',
  origin: 'Shanghai, CN',
  destination: 'Jakarta, ID',
  transshipmentPort: 'Singapore',
  motherVessel: 'MV Pacific Horizon',
  plannedMotherArrivalAt: '2026-10-04T00:00:00Z',
  feederVessel: 'MV Strait Runner',
  plannedFeederDepartureAt: '2026-10-05T00:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
  version: 0,
  connectionWindow: null,
  remainingConnectionWindow: null,
  risk: null,
  status: null,
  motherVesselPosition: null,
  feederVesselPosition: null,
  arrivalComparison: null,
};

function routeText() {
  return screen.getAllByRole('listitem').map(item => item.textContent?.replace(/\s+/g, ' ').trim());
}

const loneTracking: ShipmentTracking = {
  motherVesselName: 'MV Pacific Horizon',
  motherVessel: {
    mmsi: '563001234',
    vesselName: 'PACIFIC HORIZON',
    latitude: 1.264,
    longitude: 103.82,
    speedOverGroundKnots: 12.4,
    courseOverGroundDegrees: 175,
    trueHeadingDegrees: 176,
    ingestedAt: '2026-10-05T03:00:00Z',
  },
  motherVesselNameWarning: null,
  feederVesselName: 'MV Strait Runner',
  feederVessel: null,
  feederVesselNameWarning: null,
};

describe('shipment route display', () => {
  beforeEach(() => {
    mapCalls.jumpTo.mockClear();
    mapCalls.fitBounds.mockClear();
    mapCalls.addLayer.mockClear();
    mapCalls.setData.mockClear();
    mapCalls.setFilter.mockClear();
    mapCalls.layerHandlers.clear();
  });

  it('lists the stored route in connection order', () => {
    render(<RouteMap shipment={shipment} />);
    expect(routeText()).toEqual([
      'DEPARTURE Shanghai, CN',
      'MOTHER MV Pacific Horizon',
      'TRANSSHIPMENT Singapore',
      'FEEDER MV Strait Runner',
      'DESTINATION Jakarta, ID',
    ]);
    expect(screen.queryByText(/Not placed on the globe/)).not.toBeInTheDocument();
  });

  it('marks a blank stop as not recorded and an unknown place as off the globe', () => {
    render(<RouteMap shipment={{ ...shipment, origin: 'Not A Port', transshipmentPort: '   ' }} />);
    expect(routeText()).toEqual([
      'DEPARTURE Not A Port',
      'MOTHER MV Pacific Horizon',
      'TRANSSHIPMENT Not recorded',
      'FEEDER MV Strait Runner',
      'DESTINATION Jakarta, ID',
    ]);
    expect(screen.getByText('Not placed on the globe: Not A Port')).toBeInTheDocument();
  });

  it('marks an unassigned feeder vessel as pending', () => {
    render(<RouteMap shipment={{ ...shipment, feederVessel: null, plannedFeederDepartureAt: null }} />);
    expect(routeText()[3]).toBe('FEEDER Pending assignment');
  });

  it('places a live vessel on the planned route and shows when the fix was ingested', async () => {
    const tracking: ShipmentTracking = {
      motherVesselName: 'MV Pacific Horizon',
      motherVessel: {
        mmsi: '563001234',
        vesselName: 'PACIFIC HORIZON',
        latitude: 1.264,
        longitude: 103.82,
        speedOverGroundKnots: 12.4,
        courseOverGroundDegrees: 175,
        trueHeadingDegrees: 176,
        ingestedAt: '2026-10-05T03:00:00Z',
      },
      motherVesselNameWarning: null,
      feederVesselName: 'MV Strait Runner',
      feederVessel: null,
      feederVesselNameWarning: null,
    };
    render(<RouteMap shipment={shipment} tracking={tracking} />);
    expect(routeText().slice(0, 5)).toEqual([
      'DEPARTURE Shanghai, CN',
      'MOTHER MV Pacific Horizon',
      'TRANSSHIPMENT Singapore',
      'FEEDER MV Strait Runner',
      'DESTINATION Jakarta, ID',
    ]);
    expect(screen.getByText(/MOTHER LIVE PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn · Last updated/)).toBeInTheDocument();
    expect(screen.queryByText(/FEEDER LIVE/)).not.toBeInTheDocument();
    expect(screen.queryByText(/SEEDED/)).not.toBeInTheDocument();
    await waitFor(() => expect(mapCalls.fitBounds).toHaveBeenCalled());
    expect(mapCalls.jumpTo).not.toHaveBeenCalled();
  });

  it('labels a seeded fix and keeps its last-updated time', () => {
    render(<RouteMap shipment={shipment} tracking={{
      ...loneTracking,
      motherVessel: { ...loneTracking.motherVessel!, source: 'SEEDED' },
    }} />);
    expect(screen.getByText(/MOTHER SEEDED PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn · Last updated/)).toBeInTheDocument();
    expect(screen.queryByText(/MOTHER LIVE/)).not.toBeInTheDocument();
  });

  it('centers the globe on a single live position when no port can be placed', async () => {
    render(<RouteMap shipment={{
      ...shipment,
      origin: 'Not A Port',
      destination: 'Atlantis',
      transshipmentPort: 'Nowhere',
    }} tracking={loneTracking} />);
    await waitFor(() => expect(mapCalls.jumpTo).toHaveBeenCalledWith({ center: [103.82, 1.264], zoom: 3.4 }));
    expect(mapCalls.fitBounds).not.toHaveBeenCalled();
  });

  it('places a live marker that arrives after the map has loaded', async () => {
    const quiet = { ...shipment, origin: 'Not A Port', destination: 'Atlantis', transshipmentPort: 'Nowhere' };
    const { rerender } = render(<RouteMap shipment={quiet} />);
    await waitFor(() => expect(mapCalls.jumpTo).not.toHaveBeenCalled());
    rerender(<RouteMap shipment={quiet} tracking={loneTracking} />);
    await waitFor(() => expect(mapCalls.jumpTo).toHaveBeenCalledWith({ center: [103.82, 1.264], zoom: 3.4 }));
  });

  it('frames the first live position after the planned route, then keeps that view', async () => {
    const { rerender } = render(<RouteMap shipment={shipment} />);
    await waitFor(() => expect(mapCalls.fitBounds).toHaveBeenCalledTimes(1));
    rerender(<RouteMap shipment={shipment} tracking={loneTracking} />);
    await screen.findByText(/MOTHER LIVE PACIFIC HORIZON · 1.264, 103.82/);
    await waitFor(() => expect(mapCalls.fitBounds).toHaveBeenCalledTimes(2));
    rerender(<RouteMap shipment={shipment} tracking={{
      ...loneTracking,
      motherVessel: { ...loneTracking.motherVessel!, latitude: 8.5, longitude: 110 },
    }} />);
    await screen.findByText(/MOTHER LIVE PACIFIC HORIZON · 8.5, 110/);
    expect(mapCalls.fitBounds).toHaveBeenCalledTimes(2);
    expect(mapCalls.jumpTo).not.toHaveBeenCalled();
  });

  it('keeps the framed view when a later tracking update arrives', async () => {
    const quiet = { ...shipment, origin: 'Not A Port', destination: 'Atlantis', transshipmentPort: 'Nowhere' };
    const { rerender } = render(<RouteMap shipment={quiet} tracking={loneTracking} />);
    await waitFor(() => expect(mapCalls.jumpTo).toHaveBeenCalledTimes(1));
    rerender(<RouteMap shipment={quiet} tracking={{
      ...loneTracking,
      motherVessel: { ...loneTracking.motherVessel!, latitude: 8.5, longitude: 110 },
    }} />);
    await screen.findByText(/MOTHER LIVE PACIFIC HORIZON · 8.5, 110/);
    expect(mapCalls.jumpTo).toHaveBeenCalledTimes(1);
    expect(mapCalls.fitBounds).not.toHaveBeenCalled();
  });

  it('names every unknown place that cannot be drawn', () => {
    render(<RouteMap shipment={{ ...shipment, origin: 'Not A Port', destination: 'Atlantis' }} />);
    expect(screen.getByText('Not placed on the globe: Not A Port, Atlantis')).toBeInTheDocument();
    expect(routeText()[0]).toBe('DEPARTURE Not A Port');
    expect(routeText()[4]).toBe('DESTINATION Atlantis');
  });

  it('draws the vessel track with slowdowns highlighted and selects a clicked point', async () => {
    const history: VesselTrack = {
      vessel: 'MOTHER',
      vesselName: 'MV Pacific Horizon',
      mmsi: '563001234',
      windowHours: 24,
      from: '2026-10-04T12:00:00Z',
      to: '2026-10-05T12:00:00Z',
      slowdownThresholdKnots: 3,
      pointLimit: 500,
      truncated: false,
      points: [
        { id: 101, latitude: 1.1, longitude: 103.6, speedOverGroundKnots: 11, courseOverGroundDegrees: 90, trueHeadingDegrees: 90, ingestedAt: '2026-10-05T01:00:00Z' },
        { id: 102, latitude: 1.2, longitude: 103.7, speedOverGroundKnots: 1.5, courseOverGroundDegrees: 90, trueHeadingDegrees: 90, ingestedAt: '2026-10-05T02:00:00Z' },
        { id: 103, latitude: 1.25, longitude: 103.75, speedOverGroundKnots: 0.4, courseOverGroundDegrees: 90, trueHeadingDegrees: 90, ingestedAt: '2026-10-05T03:00:00Z' },
      ],
    };
    const onSelectPoint = vi.fn();
    const { rerender } = render(<RouteMap shipment={shipment} history={history} onSelectPoint={onSelectPoint} />);

    await waitFor(() => expect(mapCalls.setData).toHaveBeenCalledWith('vessel-track', expect.anything()));
    const ids = mapCalls.addLayer.mock.calls.map(([layer]) => (layer as { id: string }).id);
    expect(ids).toEqual(expect.arrayContaining(['vessel-track', 'vessel-track-points', 'vessel-track-selected']));
    const segments = mapCalls.setData.mock.calls.find(([id]) => id === 'vessel-track')![1] as {
      features: { properties: { slow: boolean } }[];
    };
    expect(segments.features.map(feature => feature.properties.slow)).toEqual([false, true]);

    mapCalls.layerHandlers.get('click:vessel-track-points')!({ features: [{ properties: { id: 103 } }] });
    expect(onSelectPoint).toHaveBeenCalledWith(103);

    rerender(<RouteMap shipment={shipment} history={history} selectedId={103} onSelectPoint={onSelectPoint} />);
    await waitFor(() => expect(mapCalls.setFilter).toHaveBeenLastCalledWith('vessel-track-selected', ['==', ['get', 'id'], 103]));

    rerender(<RouteMap shipment={shipment} history={{ ...history, points: [] }} selectedId={null} onSelectPoint={onSelectPoint} />);
    await waitFor(() => expect(mapCalls.setData).toHaveBeenLastCalledWith('vessel-track-points', { type: 'FeatureCollection', features: [] }));
  });
});
