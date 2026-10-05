import { useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../signup/api';
import { createShipment, type Shipment } from './api';
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

export function ShipmentForm({ token, companyName, onSessionEnded, onRegistered }: {
  token: string;
  companyName: string | null;
  onSessionEnded: () => void;
  onRegistered?: (shipment: Shipment) => void;
}) {
  const [fields, setFields] = useState<ShipmentFields>(emptyFields);
  const [errors, setErrors] = useState<ShipmentErrors>({});
  const [problem, setProblem] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [created, setCreated] = useState<Shipment | null>(null);
  const busy = useRef(false);

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
    const validation = validateShipment(fields);
    setErrors(validation);
    setProblem('');
    const invalid = fieldOrder.filter(name => validation[name]);
    if (invalid.length) {
      const first = event.currentTarget.elements.namedItem(invalid[0]);
      if (first instanceof HTMLElement) first.focus();
      return;
    }
    setCreated(null);
    busy.current = true;
    setSubmitting(true);
    try {
      const shipment = await createShipment(token, {
        shipmentReference: fields.shipmentReference.trim(),
        origin: fields.origin.trim(),
        destination: fields.destination.trim(),
        transshipmentPort: fields.transshipmentPort.trim(),
        motherVessel: fields.motherVessel.trim(),
        plannedMotherArrivalAt: toOffsetDateTime(fields.plannedMotherArrivalAt),
        feederVessel: fields.feederVessel.trim(),
        plannedFeederDepartureAt: toOffsetDateTime(fields.plannedFeederDepartureAt),
      });
      setFields(emptyFields);
      setErrors({});
      setCreated(shipment);
      onRegistered?.(shipment);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError) {
        const mapped: ShipmentErrors = {};
        for (const name of fieldOrder) if (reason.fields[name]) mapped[name] = reason.fields[name];
        if (reason.status === 409) mapped.shipmentReference = reason.message;
        setErrors(mapped);
      }
      setProblem(reason instanceof Error ? reason.message : 'The shipment could not be registered. Please try again.');
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  return (
    <section className="shipment-panel" aria-labelledby="register-shipment">
      <h3 id="register-shipment">Register a shipment</h3>
      <p className="intro">Save the transshipment port, the mother vessel, the feeder vessel, and the planned connection between them.</p>
      {problem && <div className="error-notice" role="alert">{problem}</div>}
      {created && <RegisteredShipment shipment={created} />}
      <form onSubmit={submit} className="shipment-form" noValidate aria-busy={submitting}>
        <TextField id="shipment-reference" name="shipmentReference" label="Shipment reference" placeholder="e.g. HL-1001" hint="The tracking or B/L number for this shipment." value={fields.shipmentReference} error={errors.shipmentReference} maxLength={100} autoComplete="off" spellCheck={false} disabled={submitting} onChange={change} onBlur={blur} />
        <div className="pair-grid">
          <TextField id="origin" name="origin" label="Origin" placeholder="e.g. Singapore" hint="Port where the mother vessel starts." value={fields.origin} error={errors.origin} maxLength={200} autoComplete="off" disabled={submitting} onChange={change} onBlur={blur} />
          <TextField id="destination" name="destination" label="Destination" placeholder="e.g. Jakarta" hint="Port where the feeder vessel finishes." value={fields.destination} error={errors.destination} maxLength={200} autoComplete="off" disabled={submitting} onChange={change} onBlur={blur} />
        </div>
        <TextField id="transshipment-port" name="transshipmentPort" label="Transshipment port" placeholder="e.g. Singapore" hint="Port where the shipment transfers from the mother vessel to the feeder vessel." value={fields.transshipmentPort} error={errors.transshipmentPort} maxLength={200} autoComplete="off" disabled={submitting} onChange={change} onBlur={blur} />
        <div className="pair-grid">
          <TextField id="mother-vessel" name="motherVessel" label="Mother vessel" placeholder="e.g. Ever Steady" value={fields.motherVessel} error={errors.motherVessel} maxLength={200} autoComplete="off" disabled={submitting} onChange={change} onBlur={blur} />
          <TextField id="feeder-vessel" name="feederVessel" label="Feeder vessel" placeholder="e.g. Straits Feeder" value={fields.feederVessel} error={errors.feederVessel} maxLength={200} autoComplete="off" disabled={submitting} onChange={change} onBlur={blur} />
        </div>
        <div className="pair-grid">
          <TimeField id="mother-arrival" name="plannedMotherArrivalAt" label="Planned mother-vessel arrival" hint="When the mother vessel is planned to arrive for the connection." value={fields.plannedMotherArrivalAt} error={errors.plannedMotherArrivalAt} disabled={submitting} onChange={change} onBlur={blur} />
          <TimeField id="feeder-departure" name="plannedFeederDepartureAt" label="Planned feeder-vessel departure" hint="Must be after the mother vessel arrives." value={fields.plannedFeederDepartureAt} error={errors.plannedFeederDepartureAt} disabled={submitting} onChange={change} onBlur={blur} />
        </div>
        <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Registering shipment...' : 'Register shipment'}<span aria-hidden="true">&#8594;</span></button>
        <p className="membership-note">{companyName ? `This shipment is registered for ${companyName}.` : 'This shipment is registered for your company.'}</p>
      </form>
    </section>
  );
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
        <div><dt>Feeder vessel</dt><dd>{shipment.feederVessel}</dd></div>
        <div><dt>Planned departure</dt><dd><time dateTime={shipment.plannedFeederDepartureAt}>{formatWhen(shipment.plannedFeederDepartureAt)}</time></dd></div>
      </dl>
    </article>
  );
}

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
      <input id={id} name={name} type="datetime-local" value={value} required disabled={disabled} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : `${id}-hint`} onChange={event => onChange(name, event.target.value)} onBlur={() => onBlur(name)} />
      {error ? <p className="field-error" id={`${id}-error`}>{error}</p> : <p className="field-hint" id={`${id}-hint`}>{hint}</p>}
    </div>
  );
}
