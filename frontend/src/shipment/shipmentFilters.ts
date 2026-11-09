export type ShipmentStatus = 'ACTIVE' | 'ARCHIVED';

/**
 * '' keeps newest first. 'risk' puts Critical first; within a level, shipments predicted to exceed the importer's
 * deadline come first, then the soonest feeder departure, and shipments that are not assessed come last.
 */
export type ShipmentSortOption = '' | 'risk';

export type ShipmentFilters = {
  reference: string;
  vessel: string;
  customer: string;
  arrivalDate: string;
	 status: string;
  sort: string;
  /** 'true' keeps only High and Critical shipments; '' or 'false' shows every risk level. Other values are sent as-is. */
  atRisk: string;
  /** 'true' keeps only shipments predicted to exceed the importer's deadline; '' or 'false' shows every shipment. */
  exceedsDeadline: string;
};

export const SHIPMENT_FILTER_PARAMETERS = ['reference', 'vessel', 'customer', 'arrivalDate', 'status', 'sort', 'atRisk', 'exceedsDeadline'] as const;

export const EMPTY_SHIPMENT_FILTERS: ShipmentFilters = {
  reference: '',
  vessel: '',
  customer: '',
  arrivalDate: '',
	 status: '',
  sort: '',
  atRisk: '',
  exceedsDeadline: '',
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
    exceedsDeadline: searchParams.get('exceedsDeadline') ?? '',
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
  setFlag(next, 'atRisk', filters.atRisk);
  setFlag(next, 'exceedsDeadline', filters.exceedsDeadline);
  return next;
}

export function isShipmentStatus(value: string): value is ShipmentStatus {
	return value === 'ACTIVE' || value === 'ARCHIVED';
}

export function isShipmentSortOption(value: string): value is ShipmentSortOption {
  return value === '' || value === 'risk';
}

export function shipmentFilterSearchParams(filters: ShipmentFilters): URLSearchParams {
  return applyShipmentFilters(new URLSearchParams(), filters);
}

export function hasShipmentFilters(filters: ShipmentFilters): boolean {
  return shipmentFilterSearchParams(filters).size > 0;
}

function setFlag(searchParams: URLSearchParams, parameter: string, value: string) {
  const normalized = value.trim();
  if (normalized && normalized !== 'false') searchParams.set(parameter, normalized);
}

function setText(searchParams: URLSearchParams, parameter: string, value: string) {
  const normalized = value.trim();
  if (normalized) searchParams.set(parameter, normalized);
}
