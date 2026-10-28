import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  applyShipmentFilters,
  EMPTY_SHIPMENT_FILTERS,
  isShipmentSortOption,
  isShipmentStatus,
  shipmentFiltersFromSearchParams,
  type ShipmentFilters,
} from '../shipment/shipmentFilters';

export function ShipmentFilterControls() {
  const [searchParams, setSearchParams] = useSearchParams();
  const applied = shipmentFiltersFromSearchParams(searchParams);
  const [draft, setDraft] = useState<ShipmentFilters>(applied);

  useEffect(() => {
    setDraft(applied);
  }, [applied.reference, applied.vessel, applied.customer, applied.arrivalDate, applied.status, applied.sort, applied.atRisk]);

  function update(field: keyof ShipmentFilters, value: string) {
    setDraft(current => ({ ...current, [field]: value }));
  }

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSearchParams(applyShipmentFilters(searchParams, draft));
  }

  function clear() {
    setDraft(EMPTY_SHIPMENT_FILTERS);
    setSearchParams(applyShipmentFilters(searchParams, EMPTY_SHIPMENT_FILTERS));
  }

  return (
    <form className="shipment-filters" onSubmit={apply}>
      <fieldset>
        <legend>Search and filter shipments</legend>
        <div className="shipment-filter-grid">
          <div className="field">
            <label htmlFor="shipment-filter-reference">Filter by shipment reference</label>
            <input id="shipment-filter-reference" value={draft.reference} onChange={event => update('reference', event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-vessel">Filter by vessel</label>
            <input id="shipment-filter-vessel" value={draft.vessel} onChange={event => update('vessel', event.target.value)} aria-describedby="shipment-filter-vessel-hint" />
            <p className="field-hint" id="shipment-filter-vessel-hint">Searches mother or feeder vessel.</p>
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-customer">Filter by customer</label>
            <input id="shipment-filter-customer" value={draft.customer} onChange={event => update('customer', event.target.value)} placeholder="Company name or code" />
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-arrival-date">Filter by arrival date</label>
            <input id="shipment-filter-arrival-date" type="date" value={draft.arrivalDate} onChange={event => update('arrivalDate', event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-status">Filter by status</label>
            <select id="shipment-filter-status" value={draft.status || 'ACTIVE'} onChange={event => update('status', event.target.value)}>
              {draft.status && !isShipmentStatus(draft.status) ? <option value={draft.status} disabled>Invalid status</option> : null}
              <option value="ACTIVE">Active</option>
              <option value="ARCHIVED">Archived</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="shipment-sort">Sort by</label>
            <select id="shipment-sort" value={draft.sort} onChange={event => update('sort', event.target.value)} aria-describedby="shipment-sort-hint">
              {!isShipmentSortOption(draft.sort) ? <option value={draft.sort} disabled>Invalid sort</option> : null}
              <option value="">Newest first</option>
              <option value="risk">Risk level (Critical first)</option>
            </select>
            <p className="field-hint" id="shipment-sort-hint">Risk level puts Critical first, then the soonest feeder departure. Unassessed shipments come last.</p>
          </div>
          <div className="field field-checkbox">
            <input id="shipment-filter-at-risk" type="checkbox" checked={draft.atRisk === 'true'} onChange={event => update('atRisk', event.target.checked ? 'true' : '')} aria-describedby="shipment-filter-at-risk-hint" />
            <label htmlFor="shipment-filter-at-risk">At-risk only</label>
            <p className="field-hint" id="shipment-filter-at-risk-hint">Shows High and Critical shipments.</p>
          </div>
        </div>
        <div className="shipment-filter-actions">
          <button type="submit" className="primary-button">Apply filters</button>
          <button type="button" className="secondary-button" onClick={clear}>Clear filters</button>
        </div>
      </fieldset>
    </form>
  );
}
