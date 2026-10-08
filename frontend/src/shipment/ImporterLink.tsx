import { useEffect, useState } from 'react';
import { ApiError } from '../signup/api';
import { getShipment, linkShipmentImporter, listImporterOrganisations, type ImporterOrganisation, type Shipment } from './api';
import type { Session } from '../session/session';

export function ImporterLink({ token, shipmentId, role, onSessionEnded }: {
  token: string;
  shipmentId: string;
  role: Session['role'];
  onSessionEnded: () => void;
}) {
  const forwarder = role === 'FREIGHT_FORWARDER';
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [organisations, setOrganisations] = useState<ImporterOrganisation[]>([]);
  const [selected, setSelected] = useState('');
  const [problem, setProblem] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    let active = true;
    setHidden(false);
    setProblem('');
    setNotice('');
    getShipment(token, shipmentId).then(found => {
      if (!active) return;
      setShipment(found);
      setSelected(found.importerOrganisation ? String(found.importerOrganisation.id) : '');
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      if (reason instanceof ApiError && reason.status === 404) {
        setHidden(true);
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'The importer link could not be loaded.');
    });
    if (forwarder) {
      listImporterOrganisations(token).then(found => {
        if (active) setOrganisations(found);
      }).catch((reason: unknown) => {
        if (active && reason instanceof ApiError && reason.status === 401) onSessionEnded();
      });
    }
    return () => { active = false; };
  }, [token, shipmentId, forwarder, onSessionEnded]);

  if (hidden) return null;
  if (!forwarder) {
    if (!shipment?.importerOrganisation) return null;
    return <p className="notice">Linked importer: {shipment.importerOrganisation.name}</p>;
  }
  if (!shipment && !problem) return <p className="notice">Loading importer link...</p>;

  async function save(importerCompanyId: number | null) {
    setSaving(true);
    setProblem('');
    setNotice('');
    try {
      const updated = await linkShipmentImporter(token, shipmentId, importerCompanyId);
      setShipment(updated);
      setSelected(updated.importerOrganisation ? String(updated.importerOrganisation.id) : '');
      setNotice(updated.importerOrganisation
        ? `Shipment linked to ${updated.importerOrganisation.name}.`
        : 'Importer link removed.');
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'The importer link could not be saved.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="shipment-panel" aria-labelledby="importer-link">
      <h3 id="importer-link">Link an importer</h3>
      <p className="intro">{shipment?.importerOrganisation
        ? `This shipment is linked to ${shipment.importerOrganisation.name}.`
        : 'This shipment is not linked to an importer organisation.'}</p>
      {problem && <p className="error-notice">{problem}</p>}
      {notice && <p className="success-notice" role="status">{notice}</p>}
      <div className="field">
        <label htmlFor="link-importer">Importer organisation</label>
        <select id="link-importer" value={selected} disabled={saving} aria-invalid={Boolean(problem)} onChange={event => setSelected(event.target.value)}>
          <option value="">No importer</option>
          {organisations.map(organisation => <option key={organisation.id} value={organisation.id}>{organisation.name}</option>)}
        </select>
      </div>
      <button type="button" className="primary-button" disabled={saving} onClick={() => save(selected ? Number(selected) : null)}>
        {saving ? 'Saving link...' : 'Save importer link'}<span aria-hidden="true">&#8594;</span>
      </button>
      {shipment?.importerOrganisation ? (
        <button type="button" className="retry-button" disabled={saving} onClick={() => save(null)}>Remove importer link</button>
      ) : null}
    </section>
  );
}
