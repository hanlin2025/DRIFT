export type ShipmentFields = {
  shipmentReference: string;
  origin: string;
  destination: string;
  motherVessel: string;
  plannedMotherArrivalAt: string;
  feederVessel: string;
  plannedFeederDepartureAt: string;
};

export type ShipmentErrors = Partial<Record<keyof ShipmentFields, string>>;

const TEXT_LIMITS: Record<'shipmentReference' | 'origin' | 'destination' | 'motherVessel' | 'feederVessel', number> = {
  shipmentReference: 100,
  origin: 200,
  destination: 200,
  motherVessel: 200,
  feederVessel: 200,
};

const TEXT_LABELS = {
  shipmentReference: 'Shipment reference',
  origin: 'Origin',
  destination: 'Destination',
  motherVessel: 'Mother vessel',
  feederVessel: 'Feeder vessel',
} as const;

export function validateShipment(fields: ShipmentFields): ShipmentErrors {
  const errors: ShipmentErrors = {};
  for (const name of Object.keys(TEXT_LIMITS) as (keyof typeof TEXT_LIMITS)[]) {
    const value = fields[name].trim();
    if (!value) errors[name] = `${TEXT_LABELS[name]} is required`;
    else if (value.length > TEXT_LIMITS[name]) errors[name] = `${TEXT_LABELS[name]} must be at most ${TEXT_LIMITS[name]} characters`;
  }

  const arrival = parseLocal(fields.plannedMotherArrivalAt);
  const departure = parseLocal(fields.plannedFeederDepartureAt);
  if (!arrival) errors.plannedMotherArrivalAt = 'Planned mother-vessel arrival is required';
  if (!departure) errors.plannedFeederDepartureAt = 'Planned feeder-vessel departure is required';
  else if (arrival && departure.getTime() <= arrival.getTime()) {
    errors.plannedFeederDepartureAt = 'Planned feeder-vessel departure must be after planned mother-vessel arrival';
  }
  return errors;
}

function parseLocal(value: string): Date | null {
  if (!value.trim()) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}
