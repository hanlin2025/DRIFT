import { useRef, useState } from 'react';
import { ApiError } from '../signup/api';
import { updateTransshipmentArrivalDeadline, type Shipment } from './api';
import { formatWhen } from './ShipmentForm';
import { toOffsetDateTime } from './validation';

const LOCAL_TIME = 'Shown in your local time.';
const PURPOSE = 'The time by which the mother vessel should arrive at the transshipment port. This is not the final delivery time.';
const ENTER_TIME = 'Enter a date and time.';
const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';

export function canEditTransshipmentDeadline(viewerCompanyId: number | null | undefined, shipment: Pick<Shipment, 'status' | 'importer'>) {
  return typeof viewerCompanyId === 'number'
    && shipment.importer?.id != null
    && viewerCompanyId === shipment.importer.id
    && shipment.status === 'ACTIVE';
}

export function localDateTimeInput(instant: string) {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function fieldsFrom(shipment: Shipment) {
  const instant = shipment.transshipmentArrivalDeadline?.deadline ?? null;
  return { instant, local: instant ? localDateTimeInput(instant) : '' };
}

function stripped(value: string) {
  return value.trim();
}

export function TransshipmentArrivalDeadlineFact({ shipment, viewerCompanyId, token, onSessionEnded, onShipment, portCapture, onExplicitRefresh }: {
  shipment: Shipment;
  viewerCompanyId: number | null;
  token: string;
  onSessionEnded: () => void;
  onShipment: (shipment: Shipment) => void;
  portCapture: { nonce: number; port: string } | null;
  onExplicitRefresh: () => void;
}) {
  const saved = shipment.transshipmentArrivalDeadline ?? null;
  const initial = fieldsFrom(shipment);
  const contextKey = `${shipment.id}:${viewerCompanyId ?? ''}:${shipment.importer?.id ?? ''}`;
  const [context, setContext] = useState(contextKey);
  const [capturedPort, setCapturedPort] = useState(shipment.transshipmentPort);
  const [draft, setDraft] = useState(initial.local);
  const [baselineLocal, setBaselineLocal] = useState(initial.local);
  const [baselineInstant, setBaselineInstant] = useState(initial.instant);
  const [holdDraft, setHoldDraft] = useState(false);
  const [pending, setPending] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [seenNonce, setSeenNonce] = useState(0);
  const busy = useRef(false);
  const canEdit = canEditTransshipmentDeadline(viewerCompanyId, shipment);

  if (context !== contextKey) {
    const next = fieldsFrom(shipment);
    setContext(contextKey);
    setCapturedPort(shipment.transshipmentPort);
    setDraft(next.local);
    setBaselineLocal(next.local);
    setBaselineInstant(next.instant);
    setHoldDraft(false);
    setConflict(false);
    setFieldError(null);
    setProblem(null);
  } else if (!canEdit && (holdDraft || conflict || draft !== baselineLocal || fieldError || problem)) {
    const next = fieldsFrom(shipment);
    setDraft(next.local);
    setBaselineLocal(next.local);
    setBaselineInstant(next.instant);
    setHoldDraft(false);
    setConflict(false);
    setFieldError(null);
    setProblem(null);
  } else if (!holdDraft && !pending && draft === baselineLocal) {
    const next = fieldsFrom(shipment);
    if (next.instant !== baselineInstant || next.local !== baselineLocal) {
      setDraft(next.local);
      setBaselineLocal(next.local);
      setBaselineInstant(next.instant);
    }
  }

  if (portCapture && portCapture.nonce !== seenNonce && context === contextKey) {
    setSeenNonce(portCapture.nonce);
    setCapturedPort(portCapture.port);
    setConflict(false);
  }

  async function submit(deadline: string | null) {
    if (busy.current || !canEdit) return;
    busy.current = true;
    setPending(true);
    setFieldError(null);
    setProblem(null);
    try {
      const updated = await updateTransshipmentArrivalDeadline(token, shipment.id, {
        transshipmentArrivalDeadline: deadline,
        expectedTransshipmentPort: capturedPort,
      });
      const next = fieldsFrom(updated);
      setDraft(next.local);
      setBaselineLocal(next.local);
      setBaselineInstant(next.instant);
      setHoldDraft(false);
      setConflict(false);
      onShipment(updated);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setHoldDraft(true);
      if (reason instanceof ApiError && reason.status === 409) {
        setConflict(true);
        setProblem(reason.message);
        return;
      }
      if (reason instanceof ApiError && reason.status === 400) {
        setFieldError(reason.fields.transshipmentArrivalDeadline ?? reason.fields.expectedTransshipmentPort ?? reason.message);
        return;
      }
      setProblem(reason instanceof ApiError ? reason.message : UNREACHABLE);
    } finally {
      busy.current = false;
      setPending(false);
    }
  }

  function save() {
    if (baselineInstant && draft === baselineLocal) {
      void submit(baselineInstant);
      return;
    }
    if (!draft.trim()) {
      setFieldError(ENTER_TIME);
      return;
    }
    try {
      void submit(toOffsetDateTime(draft));
    } catch {
      setFieldError(ENTER_TIME);
    }
  }

  const saveLabel = !saved ? 'Save deadline' : saved.applicable ? 'Update deadline' : `Confirm for ${capturedPort}`;
  const portDiffers = stripped(shipment.transshipmentPort) !== stripped(capturedPort);

  return (
    <div>
      <dt>Mother-vessel arrival deadline</dt>
      <dd>
        {saved
          ? <time dateTime={saved.deadline}>{formatWhen(saved.deadline)}</time>
          : <span className="is-missing">Not recorded</span>}
        <span className="deadline-note">{PURPOSE}</span>
        {saved && <span className="deadline-note">{saved.applicable
          ? `Recorded for ${saved.transshipmentPort}. Applies to the current port.`
          : `Recorded for ${saved.transshipmentPort}. The shipment's current port is ${shipment.transshipmentPort}. This deadline does not apply to the current port.`}</span>}
        {canEdit && (
          <div className="deadline-editor">
            <label htmlFor="transshipment-arrival-deadline">Deadline</label>
            <input id="transshipment-arrival-deadline" type="datetime-local" step="1" value={draft} disabled={pending}
              aria-invalid={Boolean(fieldError)} aria-describedby="transshipment-arrival-deadline-hint"
              onChange={event => setDraft(event.target.value)} />
            <p id="transshipment-arrival-deadline-hint" className="field-hint">{LOCAL_TIME}</p>
            <p className="field-hint">Confirming port: {capturedPort}</p>
            {portDiffers && <p className="field-hint" role="status">The shipment port on this page has changed. Refresh the shipment before saving.</p>}
            {fieldError && <p className="field-error" role="alert">{fieldError}</p>}
            {problem && <p className="field-error" role="alert">{problem}</p>}
            <div className="deadline-actions">
              <button type="button" className="secondary-button" onClick={save} disabled={pending}>{saveLabel}</button>
              {saved && <button type="button" className="secondary-button" onClick={() => { void submit(null); }} disabled={pending}>Clear deadline</button>}
              <button type="button" className="refresh-button" onClick={onExplicitRefresh} disabled={pending}>Refresh shipment</button>
            </div>
          </div>
        )}
      </dd>
    </div>
  );
}
