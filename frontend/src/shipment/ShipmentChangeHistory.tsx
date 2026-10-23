import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../signup/api';
import { getShipmentHistory, type ShipmentFieldChange, type ShipmentHistory, type ShipmentHistoryEntry } from './api';
import { formatWhen } from './ShipmentForm';

export const HISTORY_LOAD_FAILED = 'Change history could not be loaded.';
export const NO_CHANGES = 'No changes yet';
export const EARLIER_CHANGES_UNRECORDED = 'Changes made before history was recorded are not shown.';
const NOT_RECORDED = 'Not recorded';

const FIELD_LABELS: Record<string, string> = {
  shipmentReference: 'Shipment reference',
  origin: 'Origin',
  destination: 'Destination',
  transshipmentPort: 'Transshipment port',
  motherVessel: 'Mother vessel',
  plannedMotherArrivalAt: 'Planned mother-vessel arrival',
  motherVesselImo: 'Mother IMO',
  motherVesselMmsi: 'Mother MMSI',
  motherVesselOperator: 'Mother operator',
  feederVessel: 'Feeder vessel',
  plannedFeederDepartureAt: 'Planned feeder-vessel departure',
  feederVesselImo: 'Feeder IMO',
  feederVesselMmsi: 'Feeder MMSI',
  feederVesselOperator: 'Feeder operator',
  importer: 'Importer',
  status: 'Status',
};

const TIME_FIELDS = new Set(['plannedMotherArrivalAt', 'plannedFeederDepartureAt']);
const STATUS_LABELS: Record<string, string> = { ACTIVE: 'Active', ARCHIVED: 'Archived' };

/** Loads and lists a shipment's recorded changes; a new {@code reloadKey} loads them again. */
export function ShipmentChangeHistory({ token, shipmentId, reloadKey, onSessionEnded }: {
  token: string;
  shipmentId: string;
  reloadKey: number;
  onSessionEnded: () => void;
}) {
  const [history, setHistory] = useState<ShipmentHistory | null>(null);
  const [loading, setLoading] = useState(true);
  // The reason the last load failed, shown after HISTORY_LOAD_FAILED; null once a load succeeds.
  const [failure, setFailure] = useState<string | null>(null);
  const [retries, setRetries] = useState(0);
  const requestSeq = useRef(0);

  const load = useCallback(async () => {
    const requestId = ++requestSeq.current;
    setLoading(true);
    try {
      const found = await getShipmentHistory(token, shipmentId);
      if (requestId !== requestSeq.current) return;
      setHistory(found);
      setFailure(null);
    } catch (reason: unknown) {
      if (requestId !== requestSeq.current) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setFailure(reason instanceof Error ? reason.message : '');
    } finally {
      if (requestId === requestSeq.current) setLoading(false);
    }
  }, [token, shipmentId, onSessionEnded]);

  useEffect(() => {
    void load();
    return () => { requestSeq.current += 1; };
  }, [load, reloadKey, retries]);

  const entries = history?.entries ?? [];
  const onlyCreation = entries.length > 0 && entries.every(entry => entry.changeType === 'CREATED');

  return (
    <section className="change-history" aria-labelledby="change-history-heading">
      <h3 id="change-history-heading">Change history</h3>
      {failure !== null && (
        <div className="error-notice" role="alert">
          {HISTORY_LOAD_FAILED} {failure}<br />
          <button type="button" className="retry-button" disabled={loading} onClick={() => setRetries(value => value + 1)}>Try again</button>
        </div>
      )}
      {loading && !history && failure === null && <p className="change-history-note" role="status">Loading change history...</p>}
      {history && (
        <>
          {onlyCreation && <p className="change-history-empty">{NO_CHANGES}</p>}
          <ol className="change-history-list" aria-label="Recorded changes">
            {entries.map(entry => <HistoryEntry key={entry.id} entry={entry} />)}
          </ol>
          {history.earlierChangesUnrecorded && <p className="change-history-note">{EARLIER_CHANGES_UNRECORDED}</p>}
        </>
      )}
    </section>
  );
}

function HistoryEntry({ entry }: { entry: ShipmentHistoryEntry }) {
  return (
    <li>
      <p className="change-history-title">
        <strong>{entryLabel(entry)}</strong>
        <span> by {entry.changedBy.fullName} · <time dateTime={entry.recordedAt}>{formatWhen(entry.recordedAt)}</time></span>
      </p>
      {entry.changes.length > 0 && (
        <dl className="change-history-changes">
          {entry.changes.map(change => (
            <div key={change.field}>
              <dt>{FIELD_LABELS[change.field] ?? change.field}</dt>
              <dd>
                <FieldValue change={change} value={change.previousValue} />
                <span className="change-history-arrow" aria-label="changed to"> → </span>
                <FieldValue change={change} value={change.newValue} />
              </dd>
            </div>
          ))}
        </dl>
      )}
    </li>
  );
}

function FieldValue({ change, value }: { change: ShipmentFieldChange; value: string | null }) {
  const text = value?.trim();
  if (!text) return <span className="is-missing">{NOT_RECORDED}</span>;
  if (TIME_FIELDS.has(change.field)) return <time dateTime={text}>{formatWhen(text)}</time>;
  if (change.field === 'status') return <span>{STATUS_LABELS[text] ?? text}</span>;
  return <span>{text}</span>;
}

function entryLabel(entry: ShipmentHistoryEntry) {
  switch (entry.changeType) {
    case 'CREATED': return 'Created';
    case 'UPDATED': return 'Updated';
    case 'ARCHIVED': return 'Archived';
    case 'RESTORED': return 'Restored';
    case 'IMPORTER_LINKED': {
      const importer = entry.changes.find(change => change.field === 'importer');
      if (!importer?.previousValue?.trim()) return 'Importer linked';
      if (!importer.newValue?.trim()) return 'Importer unlinked';
      return 'Importer changed';
    }
  }
}
