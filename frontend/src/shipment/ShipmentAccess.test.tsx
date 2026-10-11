import { render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { getShipment, getShipmentTracking, listShipments, type Shipment } from './api';
import { STALE_NOTICE } from './positionNotice';
import { RouteMap } from './RouteMap';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ createShipment: vi.fn(), updateShipment: vi.fn(), listShipments: vi.fn(), getShipment: vi.fn(), getShipmentTracking: vi.fn() }));
vi.mock('maplibre-gl', () => ({
  Map: class Map {
    on(_event: string, callback: () => void) { callback(); }
    setProjection() {}
    addSource() {}
    addLayer() {}
    loaded() { return false; }
    remove() {}
    jumpTo() {}
    fitBounds() {}
  },
  Marker: class Marker {
    setLngLat() { return this; }
    addTo() { return this; }
    remove() {}
  },
  LngLatBounds: class LngLatBounds {
    extend() { return this; }
  },
}));

const account: Session = {
  token: 'forwarder-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 4,
  fullName: 'Alex Tan',
  email: 'alex@example.com',
  role: 'FREIGHT_FORWARDER',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
};

const shipment: Shipment = {
  id: 7,
  shipmentReference: 'HL-1001',
  origin: 'Shanghai, CN',
  destination: 'Jakarta, ID',
  transshipmentPort: 'Singapore',
  motherVessel: 'MV Pacific Horizon',
  plannedMotherArrivalAt: '2026-10-15T00:00:00Z',
  feederVessel: 'MV Strait Runner',
  plannedFeederDepartureAt: '2026-10-16T04:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
  version: 0,
  connectionWindow: { duration: '1 day 4 hours', totalSeconds: 100800 },
  motherVesselPosition: null,
  feederVesselPosition: null,
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function open(path: string) {
  writeSession(account);
  vi.mocked(loadSession).mockImplementation(async token => ({ ...account, token }));
  return render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter>);
}

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(listShipments).mockResolvedValue([shipment]);
  vi.mocked(getShipment).mockResolvedValue(shipment);
  vi.mocked(getShipmentTracking).mockResolvedValue({
    motherVesselName: shipment.motherVessel,
    motherVessel: null,
    feederVesselName: shipment.feederVessel,
    feederVessel: null,
  });
});

describe('map permission and missing position', () => {
  it('hides the map when the shipment is forbidden', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Your account must belong to an active company to view shipments', 403));
    open('/freight-forwarder/shipments/7');
    expect(await screen.findByRole('alert')).toHaveTextContent('Your account must belong to an active company to view shipments');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();
  });

  it('hides the map when tracking is forbidden', async () => {
    vi.mocked(getShipmentTracking).mockRejectedValue(new ApiError('Your account must belong to an active company to view shipments', 403));
    open('/freight-forwarder/shipments/7');
    expect(await screen.findByRole('alert')).toHaveTextContent('Your account must belong to an active company to view shipments');
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();
  });

  it('keeps the planned route and names the missing position', async () => {
    open('/freight-forwarder/shipments/7');
    expect(await screen.findByRole('figure', { name: 'Planned route' })).toBeInTheDocument();
    expect(screen.getByText('No live AIS position for this shipment.')).toBeInTheDocument();
  });

  it('shows the last known position with a warning when the fix is stale', () => {
    render(<RouteMap shipment={shipment} tracking={{
      motherVesselName: shipment.motherVessel,
      motherVessel: {
        mmsi: '563001234',
        vesselName: 'PACIFIC HORIZON',
        latitude: 1.264,
        longitude: 103.82,
        speedOverGroundKnots: 12.4,
        courseOverGroundDegrees: null,
        trueHeadingDegrees: null,
        ingestedAt: '2020-01-01T00:00:00Z',
      },
      feederVesselName: shipment.feederVessel,
      feederVessel: null,
    }} />);
    expect(screen.getByText(STALE_NOTICE)).toBeInTheDocument();
    expect(screen.getByText(/MOTHER LIVE PACIFIC HORIZON/)).toBeInTheDocument();
  });
});
