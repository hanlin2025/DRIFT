import { ApiError } from '../signup/api';
import { type ShipmentFields } from './validation';

export type Shipment = {
  id: number;
  shipmentReference: string;
  origin: string;
  destination: string;
  motherVessel: string;
  plannedMotherArrivalAt: string;
  feederVessel: string;
  plannedFeederDepartureAt: string;
  createdAt: string;
};

export async function createShipment(token: string, shipment: ShipmentFields): Promise<Shipment> {
  let response: Response;
  try {
    response = await fetch('/api/shipments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(shipment),
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
    throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', response.status);
  }
  const data: unknown = await response.json();
  if (!response.ok) {
    const fields: Record<string, string> = {};
    if (isRecord(data) && isRecord(data.errors)) {
      for (const [key, value] of Object.entries(data.errors)) if (typeof value === 'string') fields[key] = value;
    }
    throw new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', response.status, fields);
  }
  if (!isShipment(data)) throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
  return data;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isShipment(value: unknown): value is Shipment {
  if (!isRecord(value)) return false;
  const text = ['shipmentReference', 'origin', 'destination', 'motherVessel', 'plannedMotherArrivalAt', 'feederVessel', 'plannedFeederDepartureAt', 'createdAt'] as const;
  return typeof value.id === 'number' && text.every(key => typeof value[key] === 'string');
}
