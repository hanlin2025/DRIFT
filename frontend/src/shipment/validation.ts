export type ShipmentFields = {
  shipmentReference: string;
  origin: string;
  destination: string;
  transshipmentPort: string;
  motherVessel: string;
  plannedMotherArrivalAt: string;
  feederVessel: string;
  plannedFeederDepartureAt: string;
  motherVesselImo: string;
  motherVesselMmsi: string;
  motherVesselOperator: string;
  feederVesselImo: string;
  feederVesselMmsi: string;
  feederVesselOperator: string;
};

export type ShipmentErrors = Partial<Record<keyof ShipmentFields, string>>;

const TEXT_LIMITS: Record<'shipmentReference' | 'origin' | 'destination' | 'transshipmentPort' | 'motherVessel' | 'feederVessel', number> = {
  shipmentReference: 100,
  origin: 200,
  destination: 200,
  transshipmentPort: 200,
  motherVessel: 200,
  feederVessel: 200,
};

const TEXT_LABELS = {
  shipmentReference: 'Shipment reference',
  origin: 'Origin',
  destination: 'Destination',
  transshipmentPort: 'Transshipment port',
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

  const motherVesselImo = digits(fields.motherVesselImo, 7, 'Mother vessel IMO must be 7 digits.');
  const motherVesselMmsi = digits(fields.motherVesselMmsi, 9, 'Mother vessel MMSI must be 9 digits.');
  const feederVesselImo = digits(fields.feederVesselImo, 7, 'Feeder vessel IMO must be 7 digits.');
  const feederVesselMmsi = digits(fields.feederVesselMmsi, 9, 'Feeder vessel MMSI must be 9 digits.');
  if (motherVesselImo) errors.motherVesselImo = motherVesselImo;
  if (motherVesselMmsi) errors.motherVesselMmsi = motherVesselMmsi;
  if (feederVesselImo) errors.feederVesselImo = feederVesselImo;
  if (feederVesselMmsi) errors.feederVesselMmsi = feederVesselMmsi;
  if (fields.motherVesselOperator.trim().length > 200) {
    errors.motherVesselOperator = 'Mother vessel operator must be at most 200 characters.';
  }
  if (fields.feederVesselOperator.trim().length > 200) {
    errors.feederVesselOperator = 'Feeder vessel operator must be at most 200 characters.';
  }
  return errors;
}

function digits(value: string, length: number, message: string): string | undefined {
  const text = value.trim();
  if (!text) return undefined;
  return new RegExp(`^\\d{${length}}$`).test(text) ? undefined : message;
}

export function toOffsetDateTime(value: string): string {
  const date = parseLocal(value);
  if (!date) throw new Error('Invalid date');
  const pad = (part: number) => String(part).padStart(2, '0');
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const absolute = Math.abs(offset);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`;
}

function parseLocal(value: string): Date | null {
  if (!value.trim()) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}
