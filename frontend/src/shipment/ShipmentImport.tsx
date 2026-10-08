import { useState } from 'react';
import { ApiError } from '../signup/api';
import { downloadImportErrors, downloadShipmentTemplate, importShipments, type ShipmentImportResult } from './api';

export function ShipmentImport({ token, onSessionEnded, onImported }: {
  token: string;
  onSessionEnded: () => void;
  onImported?: () => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [problem, setProblem] = useState('');
  const [result, setResult] = useState<ShipmentImportResult | null>(null);
  const [busy, setBusy] = useState(false);

  async function downloadTemplate() {
    setProblem('');
    try {
      saveFile(await downloadShipmentTemplate(token), 'shipment-import-template.csv');
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'The template could not be downloaded.');
    }
  }

  async function upload() {
    if (!file) {
      setProblem('Choose a CSV file to import.');
      setResult(null);
      return;
    }
    setBusy(true);
    setProblem('');
    setResult(null);
    try {
      const imported = await importShipments(token, file);
      setResult(imported);
      setFile(null);
      if (imported.imported > 0) onImported?.();
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'The shipments could not be imported.');
    } finally {
      setBusy(false);
    }
  }

  async function downloadErrors() {
    if (!result) return;
    setProblem('');
    try {
      saveFile(await downloadImportErrors(token, result.id), 'shipment-import-errors.csv');
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'The error report could not be downloaded.');
    }
  }

  return (
    <section className="shipment-panel" aria-labelledby="import-shipments">
      <h3 id="import-shipments">Import shipments</h3>
      <p className="intro">Upload the CSV template. Planned times include a UTC offset, and the importer organisation is the company name.</p>
      {problem && <div className="error-notice" role="alert">{problem}</div>}
      {result && <p className="success-notice" role="status">{result.summary}</p>}
      <button type="button" className="retry-button" onClick={downloadTemplate}>Download CSV template</button>
      <div className="field">
        <label htmlFor="shipment-csv">CSV file</label>
        <input id="shipment-csv" name="file" type="file" accept=".csv,text/csv" disabled={busy} onChange={event => {
          setFile(event.target.files?.[0] ?? null);
          setProblem('');
        }} />
      </div>
      <button type="button" className="primary-button" disabled={busy} onClick={upload}>{busy ? 'Importing shipments...' : 'Import shipments'}<span aria-hidden="true">&#8594;</span></button>
      {result && result.failed > 0 ? <button type="button" className="retry-button" onClick={downloadErrors}>Download error report</button> : null}
    </section>
  );
}

function saveFile(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
