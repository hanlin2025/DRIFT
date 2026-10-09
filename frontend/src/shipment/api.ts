import { ApiError } from '../signup/api';
import { type ShipmentFields } from './validation';

export type ConnectionWindow = {
  duration: string;
  totalSeconds: number;
};

export type VesselPosition = {
  mmsi: string;
  vesselName: string | null;
  latitude: number;
  longitude: number;
  speedOverGroundKnots: number | null;
  courseOverGroundDegrees: number | null;
  trueHeadingDegrees: number | null;
  ingestedAt: string;
};

export type Shipment = {
  id: number;
  shipmentReference: string;
  origin: string;
  destination: string;
  transshipmentPort: string;
  motherVessel: string;
  plannedMotherArrivalAt: string;
  feederVessel: string;
  plannedFeederDepartureAt: string;
  createdAt: string;
  version: number;
  connectionWindow: ConnectionWindow | null;
  motherVesselPosition: VesselPosition | null;
  feederVesselPosition: VesselPosition | null;
};

const UNEXPECTED = 'DRIFT returned an unexpected response. Please try again shortly.';

export async function listShipments(token: string): Promise<Shipment[]> {
  const data = await send('/api/shipments', token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  const shipments = data.map(asShipment);
  if (shipments.some(shipment => shipment === null)) throw new ApiError(UNEXPECTED, 502);
  return shipments as Shipment[];
}

export async function getShipment(token: string, shipmentId: string): Promise<Shipment> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}`, token);
  const shipment = asShipment(data);
  if (!shipment) throw new ApiError(UNEXPECTED, 502);
  return shipment;
}

export type ShipmentTracking = {
  motherVesselName: string;
  motherVessel: VesselPosition | null;
  feederVesselName: string;
  feederVessel: VesselPosition | null;
};

export async function getShipmentTracking(token: string, shipmentId: string): Promise<ShipmentTracking> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/tracking`, token);
  const tracking = asTracking(data);
  if (!tracking) throw new ApiError(UNEXPECTED, 502);
  return tracking;
}

export async function createShipment(token: string, shipment: ShipmentFields): Promise<Shipment> {
  const data = await send('/api/shipments', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(shipment),
  });
  const created = asShipment(data);
  if (!created) throw new ApiError(UNEXPECTED, 502);
  return created;
}

export type ShipmentUpdate = ShipmentFields & { version: number };

export async function updateShipment(token: string, shipmentId: number, shipment: ShipmentUpdate): Promise<Shipment> {
  const data = await send(`/api/shipments/${encodeURIComponent(String(shipmentId))}`, token, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(shipment),
  });
  const updated = asShipment(data);
  if (!updated) throw new ApiError(UNEXPECTED, 502);
  return updated;
}

async function send(path: string, token: string, init: RequestInit = {}): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { ...init.headers, Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(15000),
      cache: 'no-store',
    });
  } catch (error) {
    if (error instanceof TypeError || (error instanceof DOMException && ['TimeoutError', 'AbortError'].includes(error.name))) {
      throw new ApiError('We could not reach DRIFT. Check your connection and try again.', 0);
    }
    throw error;
  }
  if (response.status === 401) throw new ApiError('Your session has ended. Log in again.', 401);
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new ApiError(UNEXPECTED, response.status);
  }
  const data: unknown = await response.json();
  if (!response.ok) {
    const fields: Record<string, string> = {};
    if (isRecord(data) && isRecord(data.errors)) {
      for (const [key, value] of Object.entries(data.errors)) if (typeof value === 'string') fields[key] = value;
    }
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status, fields);
  }
  return data;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isConnectionWindow(value: unknown): value is ConnectionWindow {
  return isRecord(value)
    && typeof value.duration === 'string'
    && value.duration.trim().length > 0
    && typeof value.totalSeconds === 'number'
    && Number.isFinite(value.totalSeconds);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isOptionalFiniteNumber(value: unknown): value is number | null {
  return value == null || isFiniteNumber(value);
}

function isVesselPosition(value: unknown): value is VesselPosition {
  return isRecord(value)
    && typeof value.mmsi === 'string'
    && value.mmsi.trim().length > 0
    && (value.vesselName == null || typeof value.vesselName === 'string')
    && isFiniteNumber(value.latitude)
    && isFiniteNumber(value.longitude)
    && isOptionalFiniteNumber(value.speedOverGroundKnots)
    && isOptionalFiniteNumber(value.courseOverGroundDegrees)
    && isOptionalFiniteNumber(value.trueHeadingDegrees)
    && typeof value.ingestedAt === 'string';
}

function asTracking(value: unknown): ShipmentTracking | null {
  if (!isRecord(value) || typeof value.motherVesselName !== 'string' || typeof value.feederVesselName !== 'string') return null;
  if (value.motherVessel != null && !isVesselPosition(value.motherVessel)) return null;
  if (value.feederVessel != null && !isVesselPosition(value.feederVessel)) return null;
  return {
    motherVesselName: value.motherVesselName,
    motherVessel: value.motherVessel ?? null,
    feederVesselName: value.feederVesselName,
    feederVessel: value.feederVessel ?? null,
  };
}

function asShipment(value: unknown): Shipment | null {
  if (!isRecord(value) || typeof value.id !== 'number') return null;
  const text = ['shipmentReference', 'origin', 'destination', 'transshipmentPort', 'motherVessel', 'plannedMotherArrivalAt', 'feederVessel', 'plannedFeederDepartureAt', 'createdAt'] as const;
  if (!text.every(key => typeof value[key] === 'string')) return null;
  if (!isFiniteNumber(value.version) || value.version < 0) return null;
  const window = value.connectionWindow;
  if (window != null && !isConnectionWindow(window)) return null;
  const motherVesselPosition = value.motherVesselPosition;
  const feederVesselPosition = value.feederVesselPosition;
  if (motherVesselPosition != null && !isVesselPosition(motherVesselPosition)) return null;
  if (feederVesselPosition != null && !isVesselPosition(feederVesselPosition)) return null;
  return {
    id: value.id,
    shipmentReference: value.shipmentReference as string,
    origin: value.origin as string,
    destination: value.destination as string,
    transshipmentPort: value.transshipmentPort as string,
    motherVessel: value.motherVessel as string,
    plannedMotherArrivalAt: value.plannedMotherArrivalAt as string,
    feederVessel: value.feederVessel as string,
    plannedFeederDepartureAt: value.plannedFeederDepartureAt as string,
    createdAt: value.createdAt as string,
    version: value.version,
    connectionWindow: window ?? null,
    motherVesselPosition: motherVesselPosition ?? null,
    feederVesselPosition: feederVesselPosition ?? null,
  };
}
