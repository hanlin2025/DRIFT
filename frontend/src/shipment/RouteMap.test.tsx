import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RouteMap } from './RouteMap';
import type { Shipment, ShipmentTracking } from './api';

const mapCalls = vi.hoisted(() => ({
  jumpTo: vi.fn(),
  fitBounds: vi.fn(),
}));

vi.mock('maplibre-gl', () => ({
  Map: class Map {
    on(_event: string, callback: () => void) { callback(); }
    setProjection() {}
    addSource() {}
    addLayer() {}
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
  connectionWindow: null,
  motherVesselPosition: null,
  feederVesselPosition: null,
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
  feederVesselName: 'MV Strait Runner',
  feederVessel: null,
};

describe('shipment route display', () => {
  beforeEach(() => {
    mapCalls.jumpTo.mockClear();
    mapCalls.fitBounds.mockClear();
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
      feederVesselName: 'MV Strait Runner',
      feederVessel: null,
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
    await waitFor(() => expect(mapCalls.fitBounds).toHaveBeenCalled());
    expect(mapCalls.jumpTo).not.toHaveBeenCalled();
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
});
