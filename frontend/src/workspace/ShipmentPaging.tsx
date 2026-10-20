import { useSearchParams } from 'react-router-dom';
import {
  applyShipmentPage,
  applyShipmentSort,
  DEFAULT_SHIPMENT_DIRECTION,
  DEFAULT_SHIPMENT_SORT,
} from '../shipment/shipmentFilters';

const SORTS = [
  ['plannedArrival', 'Planned arrival'],
  ['reference', 'Reference'],
  ['createdAt', 'Created date'],
] as const;

const DIRECTIONS = [
  ['asc', 'Ascending'],
  ['desc', 'Descending'],
] as const;

export function ShipmentSortControls() {
  const [searchParams, setSearchParams] = useSearchParams();
  const sort = searchParams.get('sort') ?? DEFAULT_SHIPMENT_SORT;
  const direction = searchParams.get('direction') ?? DEFAULT_SHIPMENT_DIRECTION;
  const knownSort = SORTS.some(([value]) => value === sort);
  const knownDirection = DIRECTIONS.some(([value]) => value === direction);

  return (
    <div className="shipment-sort">
      <div className="field">
        <label htmlFor="shipment-sort">Sort shipments</label>
        <select id="shipment-sort" value={sort} onChange={event => setSearchParams(applyShipmentSort(searchParams, 'sort', event.target.value))}>
          {knownSort ? null : <option value={sort}>Invalid sort</option>}
          {SORTS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="shipment-direction">Sort direction</label>
        <select id="shipment-direction" value={direction} onChange={event => setSearchParams(applyShipmentSort(searchParams, 'direction', event.target.value))}>
          {knownDirection ? null : <option value={direction}>Invalid direction</option>}
          {DIRECTIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </div>
    </div>
  );
}

export function ShipmentPageControls({ page, size, total, itemCount }: {
  page: number;
  size: number;
  total: number;
  itemCount: number;
}) {
  const [searchParams, setSearchParams] = useSearchParams();
  const beyond = itemCount === 0 && total > 0;
  const start = beyond || total === 0 ? 0 : (page - 1) * size + 1;
  const end = beyond || total === 0 ? 0 : start + itemCount - 1;
  const label = start === 0 ? `0 of ${total}` : `${start}–${end} of ${total}`;

  return (
    <nav className="shipment-paging" aria-label="Shipment pages">
      <p>{label}</p>
      <button type="button" disabled={page <= 1} onClick={() => setSearchParams(applyShipmentPage(searchParams, page - 1))}>Previous</button>
      <button type="button" disabled={page * size >= total} onClick={() => setSearchParams(applyShipmentPage(searchParams, page + 1))}>Next</button>
    </nav>
  );
}
