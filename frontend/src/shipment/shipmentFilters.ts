export type ShipmentFilters = {
  reference: string;
  vessel: string;
  customer: string;
  arrivalDate: string;
};

export const SHIPMENT_FILTER_PARAMETERS = ['reference', 'vessel', 'customer', 'arrivalDate'] as const;

export const EMPTY_SHIPMENT_FILTERS: ShipmentFilters = {
  reference: '',
  vessel: '',
  customer: '',
  arrivalDate: '',
};

export function shipmentFiltersFromSearchParams(searchParams: URLSearchParams): ShipmentFilters {
  return {
    reference: searchParams.get('reference') ?? '',
    vessel: searchParams.get('vessel') ?? '',
    customer: searchParams.get('customer') ?? '',
    arrivalDate: searchParams.get('arrivalDate') ?? '',
  };
}

export function applyShipmentFilters(searchParams: URLSearchParams, filters: ShipmentFilters): URLSearchParams {
  const next = new URLSearchParams(searchParams);
  for (const parameter of SHIPMENT_FILTER_PARAMETERS) next.delete(parameter);

  setText(next, 'reference', filters.reference);
  setText(next, 'vessel', filters.vessel);
  setText(next, 'customer', filters.customer);
  setText(next, 'arrivalDate', filters.arrivalDate);
  return next;
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
