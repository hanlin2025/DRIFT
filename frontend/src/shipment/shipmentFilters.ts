export type ShipmentStatus = 'ACTIVE' | 'ARCHIVED';

export type ShipmentFilters = {
  reference: string;
  vessel: string;
  customer: string;
  arrivalDate: string;
	 status: string;
};

export const SHIPMENT_FILTER_PARAMETERS = ['reference', 'vessel', 'customer', 'arrivalDate', 'status'] as const;

export const EMPTY_SHIPMENT_FILTERS: ShipmentFilters = {
  reference: '',
  vessel: '',
  customer: '',
  arrivalDate: '',
	 status: '',
};

export function shipmentFiltersFromSearchParams(searchParams: URLSearchParams): ShipmentFilters {
  return {
    reference: searchParams.get('reference') ?? '',
    vessel: searchParams.get('vessel') ?? '',
    customer: searchParams.get('customer') ?? '',
    arrivalDate: searchParams.get('arrivalDate') ?? '',
	 status: searchParams.get('status') ?? '',
  };
}

export function applyShipmentFilters(searchParams: URLSearchParams, filters: ShipmentFilters): URLSearchParams {
  const next = new URLSearchParams(searchParams);
  for (const parameter of SHIPMENT_FILTER_PARAMETERS) next.delete(parameter);
  next.delete('page');

  setText(next, 'reference', filters.reference);
  setText(next, 'vessel', filters.vessel);
  setText(next, 'customer', filters.customer);
  setText(next, 'arrivalDate', filters.arrivalDate);
	 setText(next, 'status', filters.status);
  return next;
}

export function isShipmentStatus(value: string): value is ShipmentStatus {
	return value === 'ACTIVE' || value === 'ARCHIVED';
}

export function shipmentFilterSearchParams(filters: ShipmentFilters): URLSearchParams {
  return applyShipmentFilters(new URLSearchParams(), filters);
}

export function hasShipmentFilters(filters: ShipmentFilters): boolean {
  return shipmentFilterSearchParams(filters).size > 0;
}

export const DEFAULT_SHIPMENT_SORT = 'createdAt';
export const DEFAULT_SHIPMENT_DIRECTION = 'desc';

export type ShipmentPageQuery = {
  reference: string;
  vessel: string;
  customer: string;
  arrivalDate: string;
  status: string;
  page: string;
  sort: string | null;
  direction: string | null;
};

export function shipmentPageQueryFromSearchParams(searchParams: URLSearchParams): ShipmentPageQuery {
  const filters = shipmentFiltersFromSearchParams(searchParams);
  return {
    ...filters,
    page: searchParams.has('page') ? searchParams.get('page') ?? '' : '1',
    sort: searchParams.has('sort') ? searchParams.get('sort') ?? '' : null,
    direction: searchParams.has('direction') ? searchParams.get('direction') ?? '' : null,
  };
}

export function shipmentPageSearchParams(query: ShipmentPageQuery): URLSearchParams {
  const params = shipmentFilterSearchParams(query);
  params.set('page', query.page);
  if (query.sort !== null) params.set('sort', query.sort);
  if (query.direction !== null) params.set('direction', query.direction);
  return params;
}

export function applyShipmentPage(searchParams: URLSearchParams, page: number): URLSearchParams {
  const next = new URLSearchParams(searchParams);
  if (page <= 1) next.delete('page');
  else next.set('page', String(page));
  return next;
}

export function applyShipmentSort(searchParams: URLSearchParams, field: 'sort' | 'direction', value: string): URLSearchParams {
  const next = new URLSearchParams(searchParams);
  next.delete('page');
  const isDefault = field === 'sort' ? value === DEFAULT_SHIPMENT_SORT : value === DEFAULT_SHIPMENT_DIRECTION;
  if (isDefault) next.delete(field);
  else next.set(field, value);
  return next;
}

function setText(searchParams: URLSearchParams, parameter: string, value: string) {
  const normalized = value.trim();
  if (normalized) searchParams.set(parameter, normalized);
}
