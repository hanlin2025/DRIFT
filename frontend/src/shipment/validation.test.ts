import { describe, expect, it } from 'vitest';
import { validateShipment, type ShipmentFields } from './validation';

const valid: ShipmentFields = {
  shipmentReference: ' HL-1001 ',
  origin: 'Singapore',
  destination: 'Jakarta',
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-02T08:00',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-03T18:00',
};

describe('shipment validation', () => {
  it('accepts a complete itinerary and ignores surrounding spaces', () => {
    expect(validateShipment(valid)).toEqual({});
  });

  it('requires every field and uses the same messages as the shipment API', () => {
    expect(validateShipment({
      shipmentReference: ' ',
      origin: '',
      destination: '',
      motherVessel: '',
      plannedMotherArrivalAt: '',
      feederVessel: '',
      plannedFeederDepartureAt: '',
    })).toEqual({
      shipmentReference: 'Shipment reference is required',
      origin: 'Origin is required',
      destination: 'Destination is required',
      motherVessel: 'Mother vessel is required',
      plannedMotherArrivalAt: 'Planned mother-vessel arrival is required',
      feederVessel: 'Feeder vessel is required',
      plannedFeederDepartureAt: 'Planned feeder-vessel departure is required',
    });
  });

  it('rejects a feeder departure that is not after the mother arrival', () => {
    expect(validateShipment({ ...valid, plannedFeederDepartureAt: '2026-10-02T08:00' }).plannedFeederDepartureAt)
      .toBe('Planned feeder-vessel departure must be after planned mother-vessel arrival');
  });

  it('rejects a reference longer than the column allows', () => {
    expect(validateShipment({ ...valid, shipmentReference: 'R'.repeat(101) }).shipmentReference)
      .toBe('Shipment reference must be at most 100 characters');
  });
});
