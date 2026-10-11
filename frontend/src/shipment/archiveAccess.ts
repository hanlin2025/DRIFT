import type { Role } from '../session/session';
import type { ApiError } from '../signup/api';

export type ArchiveViewer = {
  role: Role;
  companyId: number | null;
};

export type ShipmentArchive = {
  token: string;
  viewer: ArchiveViewer;
  onSessionEnded: () => void;
  onRemoved: (message: string) => void;
  onFailed: (error: ApiError) => void;
};

export function canArchiveShipment(viewer: ArchiveViewer, shipmentCompanyId: number | null | undefined) {
  if (viewer.role !== 'ADMIN' && viewer.role !== 'LOGISTICS_MANAGER') return false;
  if (!validId(viewer.companyId) || !validId(shipmentCompanyId)) return false;
  return viewer.companyId === shipmentCompanyId;
}

function validId(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}
