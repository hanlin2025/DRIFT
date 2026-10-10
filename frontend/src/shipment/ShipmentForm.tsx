import { useRef, useState, type FormEvent } from 'react';
import { readSession } from '../session/session';
import { ApiError } from '../signup/api';
import { ImporterLinkField } from './ImporterLinkField';
import { createShipment, linkShipmentImporter, updateShipment, type ImporterOrganisation, type Shipment } from './api';
import { toOffsetDateTime, validateShipment, type ShipmentErrors, type ShipmentFields } from './validation';

const emptyFields: ShipmentFields = {
  shipmentReference: '',
  origin: '',
  destination: '',
  transshipmentPort: '',
  motherVessel: '',
  plannedMotherArrivalAt: '',
  feederVessel: '',
  plannedFeederDepartureAt: '',
};

const fieldOrder = Object.keys(emptyFields) as (keyof ShipmentFields)[];
// Keep this aligned with StaleShipmentVersionException.MESSAGE in the backend.
const STALE_UPDATE_MESSAGE = 'This shipment was updated by another user. Refresh it and try again.';
const STALE_UPDATE_NOTICE = 'This shipment was updated by someone else while you were editing it. Reload the latest details before trying again.';

export function ShipmentForm({ token, companyName, onSessionEnded, onRegistered, shipment, onUpdated, onCancel, onReload }: {
  token: string;
  companyName?: string | null;
  onSessionEnded: () => void;
  onRegistered?: (shipment: Shipment) => void;
  shipment?: Shipment;
  onUpdated?: (shipment: Shipment) => void;
  onCancel?: () => void;
  onReload?: () => void;
}) {
  const editing = shipment !== undefined;
  const [fields, setFields] = useState<ShipmentFields>(() => shipment ? fieldsFromShipment(shipment) : emptyFields);
  const [errors, setErrors] = useState<ShipmentErrors>({});
  const [problem, setProblem] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [created, setCreated] = useState<Shipment | null>(null);
  const [stale, setStale] = useState(false);
  const [selectedImporter, setSelectedImporter] = useState<ImporterOrganisation | null>(shipment?.importer ?? null);
  const [importerQuery, setImporterQuery] = useState(shipment?.importer?.name ?? '');
  const [importerError, setImporterError] = useState('');
  const [linkNotice, setLinkNotice] = useState('');
  const [linkHold, setLinkHold] = useState<Shipment | null>(null);
  const [linkedNow, setLinkedNow] = useState(false);
  const [savedRecord, setSavedRecord] = useState<Shipment | undefined>(shipment);
  const busy = useRef(false);
  const session = readSession();
  const canLink = session?.token === token && session.role === 'FREIGHT_FORWARDER';

  function editImporterQuery(value: string) {
    setImporterQuery(value);
    setImporterError('');
    setLinkNotice('');
    if (!selectedImporter || value !== selectedImporter.name) setSelectedImporter(null);
    if (value.trim() === '' && linkHold?.importer == null) setLinkHold(null);
  }

  function chooseImporter(organisation: ImporterOrganisation) {
    setSelectedImporter(organisation);
    setImporterQuery(organisation.name);
    setImporterError('');
    setLinkNotice('');
  }

  function change(field: keyof ShipmentFields, value: string) {
    const next = { ...fields, [field]: value };
    setFields(next);
    const timing = field === 'plannedMotherArrivalAt' || field === 'plannedFeederDepartureAt';
    if (errors[field] || (timing && (errors.plannedMotherArrivalAt || errors.plannedFeederDepartureAt))) {
      const updated = validateShipment(next);
      setErrors(previous => ({
        ...previous,
        [field]: updated[field],
        ...(timing ? { plannedMotherArrivalAt: updated.plannedMotherArrivalAt, plannedFeederDepartureAt: updated.plannedFeederDepartureAt } : {}),
      }));
    }
  }

  function blur(field: keyof ShipmentFields) {
    const updated = validateShipment(fields);
    const timing = field === 'plannedMotherArrivalAt' || field === 'plannedFeederDepartureAt';
    setErrors(previous => ({
      ...previous,
      [field]: updated[field],
      ...(timing ? { plannedMotherArrivalAt: updated.plannedMotherArrivalAt, plannedFeederDepartureAt: updated.plannedFeederDepartureAt } : {}),
    }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    setProblem('');
    setStale(false);
    setLinkNotice('');
    setImporterError('');
    const unresolved = canLink && importerQuery.trim() !== '' && selectedImporter == null;
    if (unresolved) setImporterError('Select an importer organisation from the list.');
    if (!linkHold) {
      const validation = validateShipment(fields);
      setErrors(validation);
      const invalid = fieldOrder.filter(name => validation[name]);
      if (invalid.length) {
        const first = event.currentTarget.elements.namedItem(invalid[0]);
        if (first instanceof HTMLElement) first.focus();
        return;
      }
    }
    if (unresolved) {
      document.getElementById('link-importer')?.focus();
      return;
    }
    if (!linkHold) setCreated(null);
    busy.current = true;
    setSubmitting(true);
    const baseline = linkHold ?? savedRecord ?? shipment;
    const previousId = baseline?.importer?.id ?? null;
    const nextId = canLink ? selectedImporter?.id ?? null : previousId;
    let current = baseline;
    try {
      if (!linkHold) {
        const request = {
          shipmentReference: fields.shipmentReference.trim(),
          origin: fields.origin.trim(),
          destination: fields.destination.trim(),
          transshipmentPort: fields.transshipmentPort.trim(),
          motherVessel: fields.motherVessel.trim(),
          plannedMotherArrivalAt: toOffsetDateTime(fields.plannedMotherArrivalAt),
          feederVessel: fields.feederVessel.trim(),
          plannedFeederDepartureAt: toOffsetDateTime(fields.plannedFeederDepartureAt),
        };
        current = editing
          ? await updateShipment(token, baseline!.id, { ...request, version: baseline!.version })
          : await createShipment(token, request);
        setSavedRecord(current);
        if (!editing) onRegistered?.(current);
      }
      if (canLink && previousId !== nextId && current) {
        try {
          current = await linkShipmentImporter(token, current.id, nextId);
          setSavedRecord(current);
          setLinkHold(null);
          const linkedName = current.importer?.name ?? selectedImporter?.name;
          setLinkNotice(nextId == null || !linkedName ? 'Shipment unlinked.' : `Shipment linked to ${linkedName}`);
          setLinkedNow(true);
        } catch (reason) {
          if (!editing && current) {
            setLinkHold(current);
            setCreated(current);
          }
          throw reason;
        }
      } else if (linkHold) {
        setLinkHold(null);
      }
      setErrors({});
      if (editing) {
        if (current && !canLink) onUpdated?.(current);
        else if (current && previousId === nextId) onUpdated?.(current);
      } else if (current) {
        setFields(emptyFields);
        setSelectedImporter(null);
        setImporterQuery('');
        setCreated(current);
      }
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 404 && onReload) {
        onReload();
        return;
      }
      if (reason instanceof ApiError) {
        const staleUpdate = reason.status === 409 && reason.message === STALE_UPDATE_MESSAGE;
        const mapped: ShipmentErrors = {};
        for (const name of fieldOrder) if (reason.fields[name]) mapped[name] = reason.fields[name];
        if (reason.status === 409 && !staleUpdate) mapped.shipmentReference = reason.message;
        if (reason.message === 'Invalid or inactive importer organisation selected.') setImporterError(reason.message);
        setErrors(mapped);
        setStale(staleUpdate);
      }
      setProblem(reason instanceof ApiError && reason.status === 409 && reason.message === STALE_UPDATE_MESSAGE
        ? STALE_UPDATE_NOTICE
        : reason instanceof Error ? reason.message : `The shipment could not be ${editing ? 'updated' : 'registered'}. Please try again.`);
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  return (
    <section className="shipment-panel" aria-labelledby={editing ? 'edit-shipment' : 'register-shipment'}>
      <h3 id={editing ? 'edit-shipment' : 'register-shipment'}>{editing ? 'Edit shipment' : 'Register a shipment'}</h3>
      <p className="intro">{editing ? 'Update the stored route, vessel, and planned connection details.' : 'Save the transshipment port, the mother vessel, the feeder vessel, and the planned connection between them.'}</p>
      {linkNotice && <div className="success-notice" role="status">{linkNotice}</div>}
      {problem && <div className="error-notice" role="alert">{problem}{stale && onReload && <><br /><button type="button" className="retry-button" onClick={onReload}>Reload latest details</button></>}</div>}
      {!editing && created && <RegisteredShipment shipment={created} />}
      <form onSubmit={submit} className="shipment-form" noValidate aria-busy={submitting}>
        <TextField id="shipment-reference" name="shipmentReference" label="Shipment reference" placeholder="e.g. HL-1001" hint="The tracking or B/L number for this shipment." value={fields.shipmentReference} error={errors.shipmentReference} maxLength={100} autoComplete="off" spellCheck={false} disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
        <div className="pair-grid">
          <TextField id="origin" name="origin" label="Origin" placeholder="e.g. Singapore" hint="Port where the mother vessel starts." value={fields.origin} error={errors.origin} maxLength={200} autoComplete="off" disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
          <TextField id="destination" name="destination" label="Destination" placeholder="e.g. Jakarta" hint="Port where the feeder vessel finishes." value={fields.destination} error={errors.destination} maxLength={200} autoComplete="off" disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
        </div>
        <TextField id="transshipment-port" name="transshipmentPort" label="Transshipment port" placeholder="e.g. Singapore" hint="Port where the shipment transfers from the mother vessel to the feeder vessel." value={fields.transshipmentPort} error={errors.transshipmentPort} maxLength={200} autoComplete="off" disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
        <div className="pair-grid">
          <TextField id="mother-vessel" name="motherVessel" label="Mother vessel" placeholder="e.g. Ever Steady" value={fields.motherVessel} error={errors.motherVessel} maxLength={200} autoComplete="off" disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
          <TextField id="feeder-vessel" name="feederVessel" label="Feeder vessel" placeholder="e.g. Straits Feeder" value={fields.feederVessel} error={errors.feederVessel} maxLength={200} autoComplete="off" disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
        </div>
        <div className="pair-grid">
          <TimeField id="mother-arrival" name="plannedMotherArrivalAt" label="Planned mother-vessel arrival" hint="When the mother vessel is planned to arrive for the connection." value={fields.plannedMotherArrivalAt} error={errors.plannedMotherArrivalAt} disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
          <TimeField id="feeder-departure" name="plannedFeederDepartureAt" label="Planned feeder-vessel departure" hint="Must be after the mother vessel arrives." value={fields.plannedFeederDepartureAt} error={errors.plannedFeederDepartureAt} disabled={submitting || linkHold !== null} onChange={change} onBlur={blur} />
        </div>
        {canLink && <ImporterLinkField token={token} query={importerQuery} onQuery={editImporterQuery} selected={selectedImporter} onSelect={chooseImporter} disabled={submitting} error={importerError} onSessionEnded={onSessionEnded} />}
        <div className="form-actions">
          {editing && <button type="button" className="secondary-button" disabled={submitting} onClick={() => linkedNow && savedRecord ? onUpdated?.(savedRecord) : onCancel?.()}>{linkedNow ? 'Back to shipment' : 'Cancel'}</button>}
          <button type="submit" className="primary-button" disabled={submitting}>{submitting
            ? (linkHold ? 'Linking shipment...' : editing ? 'Saving shipment...' : 'Registering shipment...')
            : (linkHold ? 'Link shipment' : editing ? 'Save shipment' : 'Register shipment')}<span aria-hidden="true">&#8594;</span></button>
        </div>
        {!editing && <p className="membership-note">{companyName ? `This shipment is registered for ${companyName}.` : 'This shipment is registered for your company.'}</p>}
      </form>
    </section>
  );
}

function fieldsFromShipment(shipment: Shipment): ShipmentFields {
  return {
    shipmentReference: shipment.shipmentReference,
    origin: shipment.origin,
    destination: shipment.destination,
    transshipmentPort: shipment.transshipmentPort,
    motherVessel: shipment.motherVessel,
    plannedMotherArrivalAt: toLocalInput(shipment.plannedMotherArrivalAt),
    feederVessel: shipment.feederVessel ?? '',
    plannedFeederDepartureAt: toLocalInput(shipment.plannedFeederDepartureAt),
  };
}

function toLocalInput(value: string | null) {
  if (value == null) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function RegisteredShipment({ shipment }: { shipment: Shipment }) {
  return (
    <article className="shipment-record" role="status" aria-label={`Registered shipment ${shipment.shipmentReference}`}>
      <span className="overline">REGISTERED</span>
      <strong>{shipment.shipmentReference}</strong>
      <p className="shipment-route">{shipment.origin} to {shipment.destination}</p>
      <dl>
        <div><dt>Transshipment port</dt><dd>{shipment.transshipmentPort}</dd></div>
        <div><dt>Mother vessel</dt><dd>{shipment.motherVessel}</dd></div>
        <div><dt>Planned arrival</dt><dd><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></dd></div>
        <div><dt>Feeder vessel</dt><dd>{shipment.feederVessel || PENDING_ASSIGNMENT}</dd></div>
        <div><dt>Planned departure</dt><dd>{shipment.plannedFeederDepartureAt
          ? <time dateTime={shipment.plannedFeederDepartureAt}>{formatWhen(shipment.plannedFeederDepartureAt)}</time>
          : PENDING_ASSIGNMENT}</dd></div>
        {shipment.importer && <div><dt>Importer</dt><dd>{shipment.importer.name}</dd></div>}
      </dl>
    </article>
  );
}

export const PENDING_ASSIGNMENT = 'Pending assignment';

export function formatWhen(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function TextField({ id, name, label, placeholder, hint, value, error, maxLength, autoComplete, spellCheck, disabled, onChange, onBlur }: {
  id: string;
  name: keyof ShipmentFields;
  label: string;
  placeholder: string;
  hint?: string;
  value: string;
  error?: string;
  maxLength: number;
  autoComplete: string;
  spellCheck?: boolean;
  disabled: boolean;
  onChange: (field: keyof ShipmentFields, value: string) => void;
  onBlur: (field: keyof ShipmentFields) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} name={name} value={value} placeholder={placeholder} maxLength={maxLength} autoComplete={autoComplete} spellCheck={spellCheck} required disabled={disabled} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined} onChange={event => onChange(name, event.target.value)} onBlur={() => onBlur(name)} />
      {error ? <p className="field-error" id={`${id}-error`}>{error}</p> : hint ? <p className="field-hint" id={`${id}-hint`}>{hint}</p> : null}
    </div>
  );
}

function TimeField({ id, name, label, hint, value, error, disabled, onChange, onBlur }: {
  id: string;
  name: keyof ShipmentFields;
  label: string;
  hint: string;
  value: string;
  error?: string;
  disabled: boolean;
  onChange: (field: keyof ShipmentFields, value: string) => void;
  onBlur: (field: keyof ShipmentFields) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} name={name} type="datetime-local" step="1" value={value} required disabled={disabled} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : `${id}-hint`} onChange={event => onChange(name, event.target.value)} onBlur={() => onBlur(name)} />
      {error ? <p className="field-error" id={`${id}-error`}>{error}</p> : <p className="field-hint" id={`${id}-hint`}>{hint}</p>}
    </div>
  );
}
