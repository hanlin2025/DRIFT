import { ApiError } from '../signup/api';
import { shipmentFilterSearchParams, type ShipmentFilters } from './shipmentFilters';
import { type ShipmentFields } from './validation';

export type ConnectionWindow = {
  duration: string;
  totalSeconds: number;
};

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type ShipmentRisk = {
  level: RiskLevel;
  explanation: string;
};

export type ShipmentStatus = 'ACTIVE' | 'ARCHIVED';

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

export type OrganisationPage = {
  items: ImporterOrganisation[];
  page: number;
  size: number;
  total: number;
};

export type ArrivalClassification = 'AHEAD_OF_PLAN' | 'ON_SCHEDULE' | 'BEHIND_PLAN';

export type ArrivalComparison = {
  estimatedArrivalAt: string;
  limitation: string;
  speedOverGroundKnots: number;
  classification: ArrivalClassification;
  difference: string;
  differenceSeconds: number;
  differenceNanos: number;
  lastReceivedAt: string;
  possiblyOutdated: boolean;
  unavailableReason: null;
} | {
  estimatedArrivalAt: null;
  limitation: null;
  speedOverGroundKnots: null;
  classification: null;
  difference: null;
  differenceSeconds: null;
  differenceNanos: null;
  lastReceivedAt: null;
  possiblyOutdated: null;
  unavailableReason: { code: string; message: string };
};

export type Shipment = {
  id: number;
  shipmentReference: string;
  origin: string;
  destination: string;
  transshipmentPort: string;
  motherVessel: string;
  plannedMotherArrivalAt: string;
  feederVessel: string | null;
  plannedFeederDepartureAt: string | null;
  createdAt: string;
  version: number;
  companyId?: number | null;
  connectionWindow: ConnectionWindow | null;
  risk: ShipmentRisk | null;
  status: ShipmentStatus | null;
  motherVesselPosition: VesselPosition | null;
  feederVesselPosition: VesselPosition | null;
  importer?: ImporterOrganisation | null;
  motherVesselImo?: string | null;
  motherVesselMmsi?: string | null;
  motherVesselOperator?: string | null;
  feederVesselImo?: string | null;
  feederVesselMmsi?: string | null;
  feederVesselOperator?: string | null;
  motherVesselNameWarning?: string | null;
  feederVesselNameWarning?: string | null;
  arrivalComparison: ArrivalComparison | null;
};

const UNEXPECTED = 'DRIFT returned an unexpected response. Please try again shortly.';

export async function listShipments(token: string, filters?: ShipmentFilters): Promise<Shipment[]> {
  const query = filters ? shipmentFilterSearchParams(filters).toString() : '';
  const data = await send(query ? `/api/shipments?${query}` : '/api/shipments', token);
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
  motherVesselNameWarning: string | null;
  feederVesselName: string | null;
  feederVessel: VesselPosition | null;
  feederVesselNameWarning: string | null;
};

export async function archiveShipment(token: string, shipmentId: number): Promise<string> {
  const data = await send(`/api/shipments/${encodeURIComponent(String(shipmentId))}/status`, token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'ARCHIVED' }),
  });
  if (!isRecord(data) || typeof data.message !== 'string' || data.message.trim() === '') {
    throw new ApiError(UNEXPECTED, 502);
  }
  return data.message;
}

export async function getShipmentTracking(token: string, shipmentId: string): Promise<ShipmentTracking> {
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/tracking`, token);
  const tracking = asTracking(data);
  if (!tracking) throw new ApiError(UNEXPECTED, 502);
  return tracking;
}

export type TrackVessel = 'MOTHER' | 'FEEDER';
export type TrackWindow = 6 | 24 | 72;

export type TrackPoint = {
  id: number;
  latitude: number;
  longitude: number;
  speedOverGroundKnots: number | null;
  courseOverGroundDegrees: number | null;
  trueHeadingDegrees: number | null;
  ingestedAt: string;
};

export type VesselTrack = {
  vessel: TrackVessel;
  vesselName: string | null;
  mmsi: string | null;
  windowHours: number;
  from: string;
  to: string;
  slowdownThresholdKnots: number;
  pointLimit: number;
  truncated: boolean;
  points: TrackPoint[];
};

export async function getVesselTrack(token: string, shipmentId: string, vessel: TrackVessel, hours: TrackWindow): Promise<VesselTrack> {
  const query = new URLSearchParams({ vessel, hours: String(hours) }).toString();
  const data = await send(`/api/shipments/${encodeURIComponent(shipmentId)}/track-history?${query}`, token);
  const track = asTrack(data);
  if (!track) throw new ApiError(UNEXPECTED, 502);
  return track;
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

export async function searchImporterOrganisations(token: string, name: string): Promise<OrganisationPage> {
  const query = new URLSearchParams({ type: 'importer', name, page: '0', size: '20' });
  const data = await send(`/api/organisations?${query}`, token);
  const page = asOrganisationPage(data);
  if (!page) throw new ApiError(UNEXPECTED, 502);
  return page;
}

export async function linkShipmentImporter(token: string, shipmentId: number, importerCompanyId: number | null): Promise<Shipment> {
  const data = await send(`/api/shipments/${encodeURIComponent(String(shipmentId))}/link-importer`, token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ importerCompanyId }),
  });
  const linked = asShipment(data);
  if (!linked) throw new ApiError(UNEXPECTED, 502);
  return linked;
}

export type ShipmentImportJobStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export type ShipmentImportJob = {
  id: number;
  status: ShipmentImportJobStatus;
  originalFilename: string;
  fileSizeBytes: number;
  totalRows: number;
  processedRows: number;
  importedCount: number;
  failedCount: number;
  failureMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
};

export type ShipmentImportError = {
  rowNumber: number;
  column: string | null;
  code: string;
  message: string;
};

export async function submitShipmentImport(token: string, file: File): Promise<ShipmentImportJob> {
  const form = new FormData();
  form.append('file', file);
  const data = await send('/api/shipments/import', token, { method: 'POST', body: form });
  const job = asShipmentImportJob(data);
  if (!job) throw new ApiError(UNEXPECTED, 502);
  return job;
}

export async function getShipmentImportJob(token: string, jobId: string): Promise<ShipmentImportJob> {
  const data = await send(`/api/shipments/import/${encodeURIComponent(jobId)}`, token);
  const job = asShipmentImportJob(data);
  if (!job) throw new ApiError(UNEXPECTED, 502);
  return job;
}

export async function getShipmentImportErrors(token: string, jobId: string): Promise<ShipmentImportError[]> {
  const data = await send(`/api/shipments/import/${encodeURIComponent(jobId)}/errors`, token);
  if (!Array.isArray(data)) throw new ApiError(UNEXPECTED, 502);
  const errors = data.map(asShipmentImportError);
  if (errors.some(error => error === null)) throw new ApiError(UNEXPECTED, 502);
  return errors as ShipmentImportError[];
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

function isNullableText(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
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

const RISK_LEVELS: readonly unknown[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

function isRisk(value: unknown): value is ShipmentRisk {
  return isRecord(value)
    && RISK_LEVELS.includes(value.level)
    && typeof value.explanation === 'string'
    && value.explanation.trim().length > 0;
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
  if (!isRecord(value) || typeof value.motherVesselName !== 'string' || !isNullableText(value.feederVesselName)) return null;
  if (value.motherVessel != null && !isVesselPosition(value.motherVessel)) return null;
  if (value.feederVessel != null && !isVesselPosition(value.feederVessel)) return null;
  const motherVesselNameWarning = optionalIdentity(value, 'motherVesselNameWarning');
  const feederVesselNameWarning = optionalIdentity(value, 'feederVesselNameWarning');
  if (motherVesselNameWarning === undefined || feederVesselNameWarning === undefined) return null;
  return {
    motherVesselName: value.motherVesselName,
    motherVessel: value.motherVessel ?? null,
    motherVesselNameWarning,
    feederVesselName: value.feederVesselName,
    feederVessel: value.feederVessel ?? null,
    feederVesselNameWarning,
  };
}

function isTrackPoint(value: unknown): value is TrackPoint {
  return isRecord(value)
    && typeof value.id === 'number' && Number.isInteger(value.id) && value.id > 0
    && isFiniteNumber(value.latitude)
    && isFiniteNumber(value.longitude)
    && isOptionalFiniteNumber(value.speedOverGroundKnots)
    && isOptionalFiniteNumber(value.courseOverGroundDegrees)
    && isOptionalFiniteNumber(value.trueHeadingDegrees)
    && typeof value.ingestedAt === 'string';
}

function asTrack(value: unknown): VesselTrack | null {
  if (!isRecord(value) || (value.vessel !== 'MOTHER' && value.vessel !== 'FEEDER')) return null;
  if (!isNullableText(value.vesselName ?? null) || !isNullableText(value.mmsi ?? null)) return null;
  if (!isFiniteNumber(value.windowHours) || !isFiniteNumber(value.slowdownThresholdKnots) || !isFiniteNumber(value.pointLimit)) return null;
  if (typeof value.from !== 'string' || typeof value.to !== 'string' || typeof value.truncated !== 'boolean') return null;
  if (!Array.isArray(value.points) || !value.points.every(isTrackPoint)) return null;
  return {
    vessel: value.vessel,
    vesselName: (value.vesselName ?? null) as string | null,
    mmsi: (value.mmsi ?? null) as string | null,
    windowHours: value.windowHours,
    from: value.from,
    to: value.to,
    slowdownThresholdKnots: value.slowdownThresholdKnots,
    pointLimit: value.pointLimit,
    truncated: value.truncated,
    points: value.points.map(point => ({
      id: point.id,
      latitude: point.latitude,
      longitude: point.longitude,
      speedOverGroundKnots: point.speedOverGroundKnots ?? null,
      courseOverGroundDegrees: point.courseOverGroundDegrees ?? null,
      trueHeadingDegrees: point.trueHeadingDegrees ?? null,
      ingestedAt: point.ingestedAt,
    })),
  };
}

function asShipment(value: unknown): Shipment | null {
  if (!isRecord(value) || typeof value.id !== 'number') return null;
  const text = ['shipmentReference', 'origin', 'destination', 'transshipmentPort', 'motherVessel', 'plannedMotherArrivalAt', 'createdAt'] as const;
  if (!text.every(key => typeof value[key] === 'string')) return null;
  if (!isFiniteNumber(value.version) || value.version < 0) return null;
  if (!isNullableText(value.feederVessel) || !isNullableText(value.plannedFeederDepartureAt)) return null;
  const window = value.connectionWindow;
  if (window != null && !isConnectionWindow(window)) return null;
  const risk = value.risk;
  if (risk != null && !isRisk(risk)) return null;
  const motherVesselPosition = value.motherVesselPosition;
  const feederVesselPosition = value.feederVesselPosition;
  if (motherVesselPosition != null && !isVesselPosition(motherVesselPosition)) return null;
  if (feederVesselPosition != null && !isVesselPosition(feederVesselPosition)) return null;
  const importer = 'importer' in value ? organisationOf(value.importer) : null;
  if (importer === undefined) return null;
  const motherVesselImo = optionalIdentity(value, 'motherVesselImo');
  const motherVesselMmsi = optionalIdentity(value, 'motherVesselMmsi');
  const motherVesselOperator = optionalIdentity(value, 'motherVesselOperator');
  const feederVesselImo = optionalIdentity(value, 'feederVesselImo');
  const feederVesselMmsi = optionalIdentity(value, 'feederVesselMmsi');
  const feederVesselOperator = optionalIdentity(value, 'feederVesselOperator');
  const motherVesselNameWarning = optionalIdentity(value, 'motherVesselNameWarning');
  const feederVesselNameWarning = optionalIdentity(value, 'feederVesselNameWarning');
  if ([motherVesselImo, motherVesselMmsi, motherVesselOperator, feederVesselImo, feederVesselMmsi,
    feederVesselOperator, motherVesselNameWarning, feederVesselNameWarning].some(item => item === undefined)) return null;
  const arrivalComparison = arrivalComparisonOf(value);
  if (arrivalComparison === undefined) return null;
  return {
    id: value.id,
    shipmentReference: value.shipmentReference as string,
    origin: value.origin as string,
    destination: value.destination as string,
    transshipmentPort: value.transshipmentPort as string,
    motherVessel: value.motherVessel as string,
    plannedMotherArrivalAt: value.plannedMotherArrivalAt as string,
    feederVessel: value.feederVessel,
    plannedFeederDepartureAt: value.plannedFeederDepartureAt,
    createdAt: value.createdAt as string,
    version: value.version,
    companyId: companyIdOf(value.companyId),
    connectionWindow: window ?? null,
    risk: risk ?? null,
    status: shipmentStatusOf(value.status),
    motherVesselPosition: motherVesselPosition ?? null,
    feederVesselPosition: feederVesselPosition ?? null,
    importer,
    motherVesselImo,
    motherVesselMmsi,
    motherVesselOperator,
    feederVesselImo,
    feederVesselMmsi,
    feederVesselOperator,
    motherVesselNameWarning,
    feederVesselNameWarning,
    arrivalComparison,
  };
}

const ARRIVAL_CLASSIFICATIONS: readonly ArrivalClassification[] = ['AHEAD_OF_PLAN', 'ON_SCHEDULE', 'BEHIND_PLAN'];
const ARRIVAL_FIELDS = ['estimatedArrivalAt', 'limitation', 'speedOverGroundKnots', 'classification', 'difference', 'differenceSeconds', 'differenceNanos', 'lastReceivedAt', 'possiblyOutdated'] as const;

function arrivalComparisonOf(value: Record<string, unknown>): ArrivalComparison | null | undefined {
  if (!('arrivalComparison' in value) || value.arrivalComparison == null) return null;
  return parseArrivalComparison(value.arrivalComparison);
}

function parseArrivalComparison(value: unknown): ArrivalComparison | undefined {
  if (!isRecord(value) || !ARRIVAL_FIELDS.every(field => field in value) || !('unavailableReason' in value)) return undefined;
  if (value.unavailableReason == null) return availableComparison(value);
  const reason = value.unavailableReason;
  if (!ARRIVAL_FIELDS.every(field => value[field] === null) || !isUnavailableReason(reason)) return undefined;
  return {
    estimatedArrivalAt: null,
    limitation: null,
    speedOverGroundKnots: null,
    classification: null,
    difference: null,
    differenceSeconds: null,
    differenceNanos: null,
    lastReceivedAt: null,
    possiblyOutdated: null,
    unavailableReason: { code: reason.code, message: reason.message },
  };
}

function availableComparison(value: Record<string, unknown>): ArrivalComparison | undefined {
  if (value.unavailableReason !== null || !isPresentText(value.estimatedArrivalAt) || !isPresentText(value.limitation)
    || !isFiniteNumber(value.speedOverGroundKnots) || !isArrivalClassification(value.classification)
    || !isPresentText(value.difference) || !isSignedSeconds(value.differenceSeconds) || !isNanos(value.differenceNanos)
    || !signsAgree(value.differenceSeconds, value.differenceNanos) || !isPresentText(value.lastReceivedAt)
    || typeof value.possiblyOutdated !== 'boolean') return undefined;
  return {
    estimatedArrivalAt: value.estimatedArrivalAt,
    limitation: value.limitation,
    speedOverGroundKnots: value.speedOverGroundKnots,
    classification: value.classification,
    difference: value.difference,
    differenceSeconds: value.differenceSeconds,
    differenceNanos: value.differenceNanos,
    lastReceivedAt: value.lastReceivedAt,
    possiblyOutdated: value.possiblyOutdated,
    unavailableReason: null,
  };
}

function isUnavailableReason(value: unknown): value is { code: string; message: string } {
  return isRecord(value) && isPresentText(value.code) && isPresentText(value.message);
}

function isArrivalClassification(value: unknown): value is ArrivalClassification {
  return ARRIVAL_CLASSIFICATIONS.some(classification => classification === value);
}

function isPresentText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isSignedSeconds(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value);
}

function isNanos(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && Math.abs(value) <= 999_999_999;
}

function signsAgree(seconds: number, nanos: number) {
  return seconds === 0 || nanos === 0 || Math.sign(seconds) === Math.sign(nanos);
}

function shipmentStatusOf(value: unknown): ShipmentStatus | null {
  return value === 'ACTIVE' || value === 'ARCHIVED' ? value : null;
}

function companyIdOf(value: unknown) {
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) return null;
  return value;
}

function optionalIdentity(record: Record<string, unknown>, key: string): string | null | undefined {
  if (!(key in record) || record[key] == null) return null;
  return typeof record[key] === 'string' ? record[key] : undefined;
}

function organisationOf(value: unknown): ImporterOrganisation | null | undefined {
  if (value == null) return null;
  if (!isRecord(value) || !isFiniteNumber(value.id) || value.id <= 0 || typeof value.code !== 'string' || !value.code.trim()
    || typeof value.name !== 'string' || !value.name.trim()) return undefined;
  return { id: value.id, code: value.code, name: value.name };
}

function asOrganisationPage(value: unknown): OrganisationPage | null {
  if (!isRecord(value) || !Array.isArray(value.items) || !isWhole(value.page) || !isWhole(value.size) || !isWhole(value.total)) return null;
  const items: ImporterOrganisation[] = [];
  for (const item of value.items) {
    const organisation = organisationOf(item);
    if (!organisation) return null;
    items.push(organisation);
  }
  return { items, page: value.page, size: value.size, total: value.total };
}

function isWhole(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function asShipmentImportJob(value: unknown): ShipmentImportJob | null {
  if (!isRecord(value) || typeof value.id !== 'number' || !isImportStatus(value.status)
    || typeof value.originalFilename !== 'string' || !isFiniteNumber(value.fileSizeBytes)
    || !isNonNegativeInteger(value.totalRows) || !isNonNegativeInteger(value.processedRows)
    || !isNonNegativeInteger(value.importedCount) || !isNonNegativeInteger(value.failedCount)
    || !isNullableText(value.failureMessage) || typeof value.createdAt !== 'string'
    || !isNullableText(value.startedAt) || !isNullableText(value.completedAt)) return null;
  return {
    id: value.id,
    status: value.status,
    originalFilename: value.originalFilename,
    fileSizeBytes: value.fileSizeBytes,
    totalRows: value.totalRows,
    processedRows: value.processedRows,
    importedCount: value.importedCount,
    failedCount: value.failedCount,
    failureMessage: value.failureMessage,
    createdAt: value.createdAt,
    startedAt: value.startedAt,
    completedAt: value.completedAt,
  };
}

function asShipmentImportError(value: unknown): ShipmentImportError | null {
  if (!isRecord(value) || !isNonNegativeInteger(value.rowNumber) || !isNullableText(value.column)
    || typeof value.code !== 'string' || typeof value.message !== 'string') return null;
  return { rowNumber: value.rowNumber, column: value.column, code: value.code, message: value.message };
}

function isImportStatus(value: unknown): value is ShipmentImportJobStatus {
  return value === 'PENDING' || value === 'PROCESSING' || value === 'COMPLETED' || value === 'FAILED';
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}
