import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RouteMap } from './RouteMap';
import type { Shipment } from './api';

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

describe('shipment route display', () => {
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

  it('names every unknown place that cannot be drawn', () => {
    render(<RouteMap shipment={{ ...shipment, origin: 'Not A Port', destination: 'Atlantis' }} />);
    expect(screen.getByText('Not placed on the globe: Not A Port, Atlantis')).toBeInTheDocument();
    expect(routeText()[0]).toBe('DEPARTURE Not A Port');
    expect(routeText()[4]).toBe('DESTINATION Atlantis');
  });
});
