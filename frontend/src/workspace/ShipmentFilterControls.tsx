import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CalendarField } from '../components/CalendarField';
import { ChoiceMenu } from '../components/ChoiceMenu';
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
  }, [applied.reference, applied.vessel, applied.customer, applied.arrivalDate, applied.status, applied.sort, applied.atRisk, applied.exceedsDeadline]);

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
        <legend className="sr-only">Search and filter shipments</legend>
        <div className="shipment-filter-grid">
          <div className="field">
            <label htmlFor="shipment-filter-reference">Filter by shipment reference</label>
            <input id="shipment-filter-reference" value={draft.reference} onChange={event => update('reference', event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-vessel">Filter by vessel</label>
            <input id="shipment-filter-vessel" value={draft.vessel} onChange={event => update('vessel', event.target.value)} aria-describedby="shipment-filter-vessel-hint" />
            <p className="field-hint sr-only" id="shipment-filter-vessel-hint">Searches mother or feeder vessel.</p>
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-customer">Filter by customer</label>
            <input id="shipment-filter-customer" value={draft.customer} onChange={event => update('customer', event.target.value)} placeholder="Company name or code" />
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-arrival-date">Filter by arrival date</label>
            <CalendarField id="shipment-filter-arrival-date" pickerLabel="Choose arrival date" value={draft.arrivalDate} onChange={next => update('arrivalDate', next)} />
          </div>
          <div className="field">
            <label htmlFor="shipment-filter-status">Filter by status</label>
            <ChoiceMenu
              id="shipment-filter-status"
              value={draft.status || 'ACTIVE'}
              onChange={next => update('status', next)}
              options={[
                ...(draft.status && !isShipmentStatus(draft.status) ? [{ value: draft.status, label: 'Invalid status', disabled: true }] : []),
                { value: 'ACTIVE', label: 'Active' },
                { value: 'ARCHIVED', label: 'Archived' },
              ]}
            />
          </div>
          <div className="field">
            <label htmlFor="shipment-sort">Sort by</label>
            <ChoiceMenu
              id="shipment-sort"
              value={draft.sort}
              describedBy="shipment-sort-hint"
              onChange={next => update('sort', next)}
              options={[
                ...(!isShipmentSortOption(draft.sort) ? [{ value: draft.sort, label: 'Invalid sort', disabled: true }] : []),
                { value: '', label: 'Newest first' },
                { value: 'risk', label: 'Risk level (Critical first)' },
              ]}
            />
            <p className="field-hint sr-only" id="shipment-sort-hint">Risk level puts Critical first. Within a level, shipments that exceed the importer's deadline come first, then the soonest feeder departure. Unassessed shipments come last.</p>
          </div>
        </div>
        <div className="shipment-filter-actions">
          <div className="field field-checkbox">
            <input id="shipment-filter-at-risk" type="checkbox" checked={draft.atRisk === 'true'} onChange={event => update('atRisk', event.target.checked ? 'true' : '')} aria-describedby="shipment-filter-at-risk-hint" />
            <label htmlFor="shipment-filter-at-risk">At-risk only</label>
            <p className="field-hint sr-only" id="shipment-filter-at-risk-hint">Shows High and Critical shipments.</p>
          </div>
          <div className="field field-checkbox">
            <input id="shipment-filter-exceeds-deadline" type="checkbox" checked={draft.exceedsDeadline === 'true'} onChange={event => update('exceedsDeadline', event.target.checked ? 'true' : '')} aria-describedby="shipment-filter-exceeds-deadline-hint" />
            <label htmlFor="shipment-filter-exceeds-deadline">Exceeds importer deadline only</label>
            <p className="field-hint sr-only" id="shipment-filter-exceeds-deadline-hint">Shows shipments whose predicted arrival is after the importer's recorded deadline.</p>
          </div>
          <div className="filter-buttons">
            <button type="submit" className="primary-button">Apply filters</button>
            <button type="button" className="secondary-button" onClick={clear}>Clear filters</button>
          </div>
        </div>
      </fieldset>
    </form>
  );
}
