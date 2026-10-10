import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../signup/api';
import {
  getShipmentImportErrors,
  getShipmentImportJob,
  submitShipmentImport,
  type ShipmentImportError,
  type ShipmentImportJob,
} from './api';
import { shipmentImportErrorReport } from './shipmentImportReport';

export const MAX_IMPORT_FILE_BYTES = 5 * 1024 * 1024;
const POLL_DELAY_MS = 1_500;

export function ShipmentImportPage({ token, basePath, jobId, onSessionEnded }: {
  token: string;
  basePath: string;
  jobId?: string;
  onSessionEnded: () => void;
}) {
  const navigate = useNavigate();
  const [file, setFile] = useState<File | null>(null);
  const [fileProblem, setFileProblem] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [job, setJob] = useState<ShipmentImportJob | null>(null);
  const [errors, setErrors] = useState<ShipmentImportError[] | null>(null);
  const [statusProblem, setStatusProblem] = useState('');
  const [retry, setRetry] = useState(0);
  const busy = useRef(false);
  const jobPath = `${basePath}/import`;

  useEffect(() => {
    if (!jobId) {
      setJob(null);
      setErrors(null);
      setStatusProblem('');
      return;
    }
    if (!/^\d+$/.test(jobId)) {
      setJob(null);
      setErrors(null);
      setStatusProblem('This import job is unavailable.');
      return;
    }

    let active = true;
    let timer: number | undefined;
    const load = async () => {
      try {
        const current = await getShipmentImportJob(token, jobId);
        if (!active) return;
        setJob(current);
        setStatusProblem('');
        if (current.status === 'COMPLETED' && current.failedCount > 0) {
          const found = await getShipmentImportErrors(token, jobId);
          if (active) setErrors(found);
          return;
        }
        if (current.status === 'COMPLETED' || current.status === 'FAILED') return;
        timer = window.setTimeout(() => { void load(); }, POLL_DELAY_MS);
      } catch (reason) {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) {
          onSessionEnded();
          return;
        }
        setStatusProblem(statusMessage(reason));
      }
    };
    setJob(null);
    setErrors(null);
    setStatusProblem('');
    void load();
    return () => {
      active = false;
      if (timer) window.clearTimeout(timer);
    };
  }, [jobId, onSessionEnded, retry, token]);

  function selectFile(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    if (!selected) {
      setFile(null);
      setFileProblem('');
      return;
    }
    const problem = fileValidationProblem(selected);
    setFile(problem ? null : selected);
    setFileProblem(problem);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    const selectedFile = file;
    if (!selectedFile) {
      setFileProblem('Choose a CSV file to import.');
      return;
    }
    const problem = fileValidationProblem(selectedFile);
    if (problem) {
      setFileProblem(problem);
      return;
    }
    busy.current = true;
    setSubmitting(true);
    setFileProblem('');
    try {
      const accepted = await submitShipmentImport(token, selectedFile);
      navigate(`${jobPath}/${accepted.id}`);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setFileProblem(submissionMessage(reason));
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  }

  return (
    <section className="shipment-import" aria-labelledby="import-shipments">
      <Link className="back-link" to={basePath}>Back to shipments</Link>
      <p className="eyebrow">SHIPMENT PORTFOLIO</p>
      <h2 id="import-shipments">Import shipments</h2>
      <p className="intro">Upload the DRIFT CSV template to create multiple shipment plans. Importing continues safely if you leave this page.</p>
      <p><a className="template-link" href="/shipment-import-template.csv" download>Download CSV Template</a></p>
      {jobId ? <JobStatus job={job} errors={errors} problem={statusProblem} onRetry={() => setRetry(value => value + 1)} basePath={basePath} /> : (
        <form className="shipment-import-form" onSubmit={submit} noValidate aria-busy={submitting}>
          {fileProblem && <div className="error-notice" role="alert">{fileProblem}</div>}
          <label className="import-file-field" htmlFor="shipment-import-file">
            <span>CSV file</span>
            <input id="shipment-import-file" type="file" accept=".csv,text/csv" aria-label="CSV file" onChange={selectFile} disabled={submitting} />
            <small>One non-empty .csv file, up to 5 MB.</small>
          </label>
          {file && <p className="selected-file" role="status">Selected: <strong>{file.name}</strong></p>}
          <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Starting import...' : 'Import shipments'}<span aria-hidden="true">→</span></button>
        </form>
      )}
    </section>
  );
}

function JobStatus({ job, errors, problem, onRetry, basePath }: {
  job: ShipmentImportJob | null;
  errors: ShipmentImportError[] | null;
  problem: string;
  onRetry: () => void;
  basePath: string;
}) {
  if (problem) return <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={onRetry}>Retry status check</button></div>;
  if (job === null) return <div className="notice" role="status">Loading import status...</div>;
  if (job.status === 'FAILED') return <>
    <div className="error-notice" role="alert">{job.failureMessage || 'The import could not be completed.'}</div>
    <p><a className="template-link" href="/shipment-import-template.csv" download>Download CSV Template</a></p>
    <BackToPortfolio basePath={basePath} />
  </>;
  if (job.status !== 'COMPLETED') return <div className="import-progress" role="status">
    <span className="overline">{job.status === 'PENDING' ? 'IMPORT QUEUED' : 'IMPORTING SHIPMENTS'}</span>
    <strong>{job.status === 'PENDING' || job.totalRows === 0 ? 'Preparing your import...' : `${job.processedRows} of ${job.totalRows} rows processed`}</strong>
    {job.totalRows > 0 && <progress value={job.processedRows} max={job.totalRows}>{job.processedRows} of {job.totalRows}</progress>}
  </div>;
  return <>
    <div className="success-notice" role="status">{completionMessage(job)}</div>
    {job.failedCount > 0 && (errors === null ? <div className="notice" role="status">Loading row errors...</div> : <ImportErrors errors={errors} jobId={job.id} />)}
    <BackToPortfolio basePath={basePath} />
  </>;
}

function completionMessage(job: ShipmentImportJob): string {
  if (job.failedCount === 0) return `${job.importedCount} ${job.importedCount === 1 ? 'shipment' : 'shipments'} imported successfully.`;
  return `${job.importedCount} imported, ${job.failedCount} failed.`;
}

function ImportErrors({ errors, jobId }: { errors: ShipmentImportError[]; jobId: number }) {
  function download() {
    const blob = new Blob([shipmentImportErrorReport(errors)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `shipment-import-errors-${jobId}.csv`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  return <section className="import-errors" aria-labelledby="import-errors">
    <div className="import-errors-heading">
      <h3 id="import-errors">Rows needing attention</h3>
      <button type="button" className="secondary-button" onClick={download}>Download Error Report</button>
    </div>
    <div className="shipment-list">
      <table>
        <caption className="sr-only">Shipment import errors</caption>
        <thead><tr><th scope="col">Row</th><th scope="col">Column</th><th scope="col">Reason</th></tr></thead>
        <tbody>{errors.map((error, index) => <tr key={`${error.rowNumber}-${error.column}-${error.code}-${index}`}>
          <td>{error.rowNumber}</td><td>{error.column ?? '—'}</td><td>{error.message}</td>
        </tr>)}</tbody>
      </table>
    </div>
  </section>;
}

function BackToPortfolio({ basePath }: { basePath: string }) {
  return <p className="import-back"><Link className="secondary-button" to={basePath}>Back to Shipments</Link></p>;
}

function fileValidationProblem(file: File): string {
  if (!file.name.toLowerCase().endsWith('.csv')) return 'Choose a .csv file.';
  if (file.size <= 0) return 'Choose a non-empty CSV file.';
  if (file.size > MAX_IMPORT_FILE_BYTES) return 'CSV files must not exceed 5 MB.';
  return '';
}

function submissionMessage(reason: unknown): string {
  if (reason instanceof ApiError && reason.status === 403) return 'You do not have permission to import shipments.';
  return reason instanceof Error ? reason.message : 'The CSV could not be submitted. Please try again.';
}

function statusMessage(reason: unknown): string {
  if (reason instanceof ApiError && reason.status === 404) return 'This import job is unavailable.';
  if (reason instanceof ApiError && reason.status === 403) return 'You do not have permission to view this import job.';
  return reason instanceof Error ? reason.message : 'We could not retrieve this import status. Please try again.';
}
