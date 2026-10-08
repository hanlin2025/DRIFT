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

export type ImporterOrganisation = {
  id: number;
  code: string;
  name: string;
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
  connectionWindow: ConnectionWindow | null;
  motherVesselPosition: VesselPosition | null;
  feederVesselPosition: VesselPosition | null;
  importerOrganisation?: ImporterOrganisation | null;
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

export async function createShipment(token: string, shipment: ShipmentFields & { importerCompanyId?: number }): Promise<Shipment> {
  const data = await send('/api/shipments', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(shipment),
  });
  const created = asShipment(data);
  if (!created) throw new ApiError(UNEXPECTED, 502);
  return created;
}

export async function listImporterOrganisations(token: string, query?: string): Promise<ImporterOrganisation[]> {
  const path = query ? `/api/importer-organisations?q=${encodeURIComponent(query)}` : '/api/importer-organisations';
  const data = await send(path, token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  const organisations = data.map(asImporterOrganisation);
  if (organisations.some(organisation => organisation === null)) throw new ApiError(UNEXPECTED, 502);
  return organisations as ImporterOrganisation[];
}

export type ShipmentImportResult = {
  id: number;
  imported: number;
  failed: number;
  summary: string;
};

export async function downloadShipmentTemplate(token: string): Promise<Blob> {
  return sendFile('/api/shipment-imports/template', token);
}

export async function downloadImportErrors(token: string, importId: number): Promise<Blob> {
  return sendFile(`/api/shipment-imports/${importId}/errors`, token);
}

export async function importShipments(token: string, file: File): Promise<ShipmentImportResult> {
  const body = new FormData();
  body.append('file', file);
  const data = await send('/api/shipment-imports', token, { method: 'POST', body });
  if (!isRecord(data) || typeof data.id !== 'number' || typeof data.imported !== 'number'
    || typeof data.failed !== 'number' || typeof data.summary !== 'string') {
    throw new ApiError(UNEXPECTED, 502);
  }
  return { id: data.id, imported: data.imported, failed: data.failed, summary: data.summary };
}

export async function linkShipmentImporter(token: string, shipmentId: string, importerCompanyId: number | null): Promise<Shipment> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/importer`, token, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ importerCompanyId }),
  });
  const shipment = asShipment(data);
  if (!shipment) throw new ApiError(UNEXPECTED, 502);
  return shipment;
}

async function sendFile(path: string, token: string): Promise<Blob> {
  const response = await fetchAuthed(path, token);
  if (!response.ok) throw await errorFrom(response);
  return response.blob();
}

async function send(path: string, token: string, init: RequestInit = {}): Promise<unknown> {
  const response = await fetchAuthed(path, token, init);
  if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) {
    if (response.status === 401) throw new ApiError('Your session has ended. Log in again.', 401);
    if (!response.headers.get('content-type')?.includes('application/json')) {
      throw new ApiError(UNEXPECTED, response.status);
    }
  }
  const data: unknown = await response.json();
  if (!response.ok) throw errorBody(data, response.status);
  return data;
}

async function fetchAuthed(path: string, token: string, init: RequestInit = {}): Promise<Response> {
  try {
    return await fetch(path, {
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
}

async function errorFrom(response: Response): Promise<ApiError> {
  if (response.status === 401) return new ApiError('Your session has ended. Log in again.', 401);
  if (!response.headers.get('content-type')?.includes('application/json')) {
    return new ApiError(UNEXPECTED, response.status);
  }
  return errorBody(await response.json(), response.status);
}

function errorBody(data: unknown, status: number): ApiError {
  const fields: Record<string, string> = {};
  if (isRecord(data) && isRecord(data.errors)) {
    for (const [key, value] of Object.entries(data.errors)) if (typeof value === 'string') fields[key] = value;
  }
  return new ApiError(isRecord(data) && typeof data.message === 'string' ? data.message : 'Your request could not be completed.', status, fields);
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
  const window = value.connectionWindow;
  if (window != null && !isConnectionWindow(window)) return null;
  const motherVesselPosition = value.motherVesselPosition;
  const feederVesselPosition = value.feederVesselPosition;
  if (motherVesselPosition != null && !isVesselPosition(motherVesselPosition)) return null;
  if (feederVesselPosition != null && !isVesselPosition(feederVesselPosition)) return null;
  let importerOrganisation: ImporterOrganisation | null = null;
  if ('importerOrganisation' in value && value.importerOrganisation != null) {
    importerOrganisation = asImporterOrganisation(value.importerOrganisation);
    if (!importerOrganisation) return null;
  }
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
    connectionWindow: window ?? null,
    motherVesselPosition: motherVesselPosition ?? null,
    feederVesselPosition: feederVesselPosition ?? null,
    importerOrganisation,
  };
}

function asImporterOrganisation(value: unknown): ImporterOrganisation | null {
  if (!isRecord(value) || !isFiniteNumber(value.id) || typeof value.code !== 'string' || typeof value.name !== 'string') return null;
  if (!value.code.trim() || !value.name.trim()) return null;
  return { id: value.id, code: value.code, name: value.name };
}
