export type ShipmentStatus = 'ACTIVE' | 'ARCHIVED';

/**
 * '' keeps newest first. 'risk' puts Critical first with ties by the soonest feeder departure. 'priority' is the
 * ranked order that weighs risk severity, urgency to the feeder departure, and confidence in the evidence.
 * Shipments that are not assessed come last in both.
 */
export type ShipmentSortOption = '' | 'risk' | 'priority';

export type ShipmentFilters = {
  reference: string;
  vessel: string;
  customer: string;
  arrivalDate: string;
	 status: string;
  sort: string;
  /** 'true' keeps only High and Critical shipments; '' or 'false' shows every risk level. Other values are sent as-is. */
  atRisk: string;
};

export const SHIPMENT_FILTER_PARAMETERS = ['reference', 'vessel', 'customer', 'arrivalDate', 'status', 'sort', 'atRisk'] as const;

export const EMPTY_SHIPMENT_FILTERS: ShipmentFilters = {
  reference: '',
  vessel: '',
  customer: '',
  arrivalDate: '',
	 status: '',
  sort: '',
  atRisk: '',
};

export function shipmentFiltersFromSearchParams(searchParams: URLSearchParams): ShipmentFilters {
  return {
    reference: searchParams.get('reference') ?? '',
    vessel: searchParams.get('vessel') ?? '',
    customer: searchParams.get('customer') ?? '',
    arrivalDate: searchParams.get('arrivalDate') ?? '',
	 status: searchParams.get('status') ?? '',
    sort: searchParams.get('sort') ?? '',
    atRisk: searchParams.get('atRisk') ?? '',
  };
}

export function applyShipmentFilters(searchParams: URLSearchParams, filters: ShipmentFilters): URLSearchParams {
  const next = new URLSearchParams(searchParams);
  for (const parameter of SHIPMENT_FILTER_PARAMETERS) next.delete(parameter);

  setText(next, 'reference', filters.reference);
  setText(next, 'vessel', filters.vessel);
  setText(next, 'customer', filters.customer);
  setText(next, 'arrivalDate', filters.arrivalDate);
	 setText(next, 'status', filters.status);
  setText(next, 'sort', filters.sort);
  // 'false' means no filter. Any other value is kept, so the API can reject an invalid one from a direct URL.
  const atRisk = filters.atRisk.trim();
  if (atRisk && atRisk !== 'false') next.set('atRisk', atRisk);
  return next;
}

export function isShipmentStatus(value: string): value is ShipmentStatus {
	return value === 'ACTIVE' || value === 'ARCHIVED';
}

export function isShipmentSortOption(value: string): value is ShipmentSortOption {
  return value === '' || value === 'risk' || value === 'priority';
}

export function shipmentFilterSearchParams(filters: ShipmentFilters): URLSearchParams {
  return applyShipmentFilters(new URLSearchParams(), filters);
}

export function hasShipmentFilters(filters: ShipmentFilters): boolean {
  return shipmentFilterSearchParams(filters).size > 0;
}

function setText(searchParams: URLSearchParams, parameter: string, value: string) {
  const normalized = value.trim();
  if (normalized) searchParams.set(parameter, normalized);
}
