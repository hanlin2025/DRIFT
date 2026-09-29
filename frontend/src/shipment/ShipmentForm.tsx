import { useState, type FormEvent } from 'react';
import { validateShipment, type ShipmentErrors, type ShipmentFields } from './validation';

const emptyFields: ShipmentFields = {
  shipmentReference: '',
  origin: '',
  destination: '',
  motherVessel: '',
  plannedMotherArrivalAt: '',
  feederVessel: '',
  plannedFeederDepartureAt: '',
};

const fieldOrder = Object.keys(emptyFields) as (keyof ShipmentFields)[];

export function ShipmentForm({ companyName }: { companyName: string | null }) {
  const [fields, setFields] = useState<ShipmentFields>(emptyFields);
  const [errors, setErrors] = useState<ShipmentErrors>({});

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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validateShipment(fields);
    setErrors(validation);
    const invalid = fieldOrder.filter(name => validation[name]);
    if (invalid.length) {
      const first = event.currentTarget.elements.namedItem(invalid[0]);
      if (first instanceof HTMLElement) first.focus();
    }
  }

  return (
    <section className="shipment-panel" aria-labelledby="register-shipment">
      <h3 id="register-shipment">Register a shipment</h3>
      <p className="intro">Save the mother vessel, the feeder vessel, and the planned connection between them.</p>
      <form onSubmit={submit} className="shipment-form" noValidate>
        <TextField id="shipment-reference" name="shipmentReference" label="Shipment reference" placeholder="e.g. HL-1001" hint="The tracking or B/L number for this shipment." value={fields.shipmentReference} error={errors.shipmentReference} maxLength={100} autoComplete="off" spellCheck={false} onChange={change} onBlur={blur} />
        <div className="pair-grid">
          <TextField id="origin" name="origin" label="Origin" placeholder="e.g. Singapore" hint="Port where the mother vessel starts." value={fields.origin} error={errors.origin} maxLength={200} autoComplete="off" onChange={change} onBlur={blur} />
          <TextField id="destination" name="destination" label="Destination" placeholder="e.g. Jakarta" hint="Port where the feeder vessel finishes." value={fields.destination} error={errors.destination} maxLength={200} autoComplete="off" onChange={change} onBlur={blur} />
        </div>
        <div className="pair-grid">
          <TextField id="mother-vessel" name="motherVessel" label="Mother vessel" placeholder="e.g. Ever Steady" value={fields.motherVessel} error={errors.motherVessel} maxLength={200} autoComplete="off" onChange={change} onBlur={blur} />
          <TextField id="feeder-vessel" name="feederVessel" label="Feeder vessel" placeholder="e.g. Straits Feeder" value={fields.feederVessel} error={errors.feederVessel} maxLength={200} autoComplete="off" onChange={change} onBlur={blur} />
        </div>
        <div className="pair-grid">
          <TimeField id="mother-arrival" name="plannedMotherArrivalAt" label="Planned mother-vessel arrival" hint="When the mother vessel is planned to arrive for the connection." value={fields.plannedMotherArrivalAt} error={errors.plannedMotherArrivalAt} onChange={change} onBlur={blur} />
          <TimeField id="feeder-departure" name="plannedFeederDepartureAt" label="Planned feeder-vessel departure" hint="Must be after the mother vessel arrives." value={fields.plannedFeederDepartureAt} error={errors.plannedFeederDepartureAt} onChange={change} onBlur={blur} />
        </div>
        <button type="submit" className="primary-button">Register shipment<span aria-hidden="true">&#8594;</span></button>
        <p className="membership-note">{companyName ? `This shipment is registered for ${companyName}.` : 'This shipment is registered for your company.'}</p>
      </form>
    </section>
  );
}

function TextField({ id, name, label, placeholder, hint, value, error, maxLength, autoComplete, spellCheck, onChange, onBlur }: {
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
  onChange: (field: keyof ShipmentFields, value: string) => void;
  onBlur: (field: keyof ShipmentFields) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} name={name} value={value} placeholder={placeholder} maxLength={maxLength} autoComplete={autoComplete} spellCheck={spellCheck} required aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined} onChange={event => onChange(name, event.target.value)} onBlur={() => onBlur(name)} />
      {error ? <p className="field-error" id={`${id}-error`}>{error}</p> : hint ? <p className="field-hint" id={`${id}-hint`}>{hint}</p> : null}
    </div>
  );
}

function TimeField({ id, name, label, hint, value, error, onChange, onBlur }: {
  id: string;
  name: keyof ShipmentFields;
  label: string;
  hint: string;
  value: string;
  error?: string;
  onChange: (field: keyof ShipmentFields, value: string) => void;
  onBlur: (field: keyof ShipmentFields) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} name={name} type="datetime-local" value={value} required aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : `${id}-hint`} onChange={event => onChange(name, event.target.value)} onBlur={() => onBlur(name)} />
      {error ? <p className="field-error" id={`${id}-error`}>{error}</p> : <p className="field-hint" id={`${id}-hint`}>{hint}</p>}
    </div>
  );
}
