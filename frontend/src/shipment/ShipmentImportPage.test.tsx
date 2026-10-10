import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import {
  getShipmentImportErrors,
  getShipmentImportJob,
  listShipments,
  submitShipmentImport,
  type ShipmentImportJob,
} from './api';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('./api', () => ({
  createShipment: vi.fn(),
  getShipment: vi.fn(),
  getShipmentTracking: vi.fn(),
  getShipmentImportErrors: vi.fn(),
  getShipmentImportJob: vi.fn(),
  listShipments: vi.fn(),
  submitShipmentImport: vi.fn(),
  updateShipment: vi.fn(),
}));

const forwarder: Session = {
  token: 'forwarder-token', expiresAt: '2099-01-01T00:00:00Z', id: 1, fullName: 'Ava Forwarder', email: 'ava@example.com',
  role: 'FREIGHT_FORWARDER', company: { id: 1, code: 'FORWARDER_A', name: 'Forwarder A' },
};
const job: ShipmentImportJob = {
  id: 42, status: 'PENDING', originalFilename: 'shipments.csv', fileSizeBytes: 200, totalRows: 0, processedRows: 0,
  importedCount: 0, failedCount: 0, failureMessage: null, createdAt: '2026-10-10T00:00:00Z', startedAt: null, completedAt: null,
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function open(account = forwarder, path = '/freight-forwarder/import') {
  writeSession(account);
  vi.mocked(loadSession).mockImplementation(async token => ({ ...account, token }));
  return render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter>);
}

beforeEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
  clearSession();
  vi.mocked(listShipments).mockResolvedValue([]);
  vi.mocked(getShipmentImportJob).mockResolvedValue(job);
});

describe('shipment import page', () => {
  it('shows the import entry only to freight forwarders and guards direct import routes by exact role', async () => {
    const initial = open();
    expect(await screen.findByRole('link', { name: 'Import shipments' })).toHaveAttribute('href', '/freight-forwarder/import');
    initial.unmount();

    for (const role of ['IMPORTER', 'LOGISTICS_MANAGER', 'ADMIN'] as const) {
      clearSession();
      const account = { ...forwarder, role };
      writeSession(account);
      vi.mocked(loadSession).mockImplementation(async token => ({ ...account, token }));
      const view = render(<MemoryRouter initialEntries={['/freight-forwarder/import/42']}><AppRoutes /><Location /></MemoryRouter>);
      await waitFor(() => expect(screen.getByTestId('location')).not.toHaveTextContent('/freight-forwarder/import/42'));
      view.unmount();
      cleanup();
    }
  });

  it('selects a valid CSV, submits it, and moves to the resumable job route', async () => {
    vi.mocked(submitShipmentImport).mockResolvedValue(job);
    open();
    const file = new File(['row'], 'shipments.csv', { type: 'text/csv' });
    const user = userEvent.setup();
    await user.upload(await screen.findByLabelText('CSV file'), file);
    expect(screen.getByText('Selected:', { exact: false })).toHaveTextContent('Selected: shipments.csv');
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(submitShipmentImport).toHaveBeenCalledWith('forwarder-token', file);
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/import/42'));
  });

  it('rejects missing, non-CSV, and oversized selections before submission', async () => {
    open();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Import shipments' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a CSV file to import.');

    const input = screen.getByLabelText('CSV file');
    fireEvent.change(input, { target: { files: [new File(['text'], 'shipments.txt', { type: 'text/plain' })] } });
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a .csv file.');

    const tooLarge = new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'large.csv', { type: 'text/csv' });
    fireEvent.change(input, { target: { files: [tooLarge] } });
    expect(screen.getByRole('alert')).toHaveTextContent('CSV files must not exceed 5 MB.');
    expect(submitShipmentImport).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: 'Download CSV Template' })).toHaveAttribute('href', '/shipment-import-template.csv');
  });

  it('polls a direct job route sequentially and fetches row errors only after completed partial success', async () => {
    vi.useFakeTimers();
    const processing = { ...job, status: 'PROCESSING' as const, totalRows: 3, processedRows: 1 };
    const completed = { ...processing, status: 'COMPLETED' as const, processedRows: 3, importedCount: 2, failedCount: 1, completedAt: '2026-10-10T00:02:00Z' };
    vi.mocked(getShipmentImportJob).mockResolvedValueOnce(processing).mockResolvedValueOnce(completed);
    vi.mocked(getShipmentImportErrors).mockResolvedValue([{ rowNumber: 3, column: 'Origin Port', code: 'MISSING_VALUE', message: 'Origin Port is required' }]);
    open(forwarder, '/freight-forwarder/import/42');
    await act(async () => {});
    expect(screen.getByText('1 of 3 rows processed')).toBeInTheDocument();
    expect(getShipmentImportErrors).not.toHaveBeenCalled();

    await act(async () => { await vi.advanceTimersByTimeAsync(1_500); });
    expect(screen.getByText('2 imported, 1 failed.')).toBeInTheDocument();
    expect(getShipmentImportJob).toHaveBeenCalledTimes(2);
    expect(getShipmentImportErrors).toHaveBeenCalledWith('forwarder-token', '42');
    expect(screen.getByText('Origin Port is required')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download Error Report' })).toBeInTheDocument();
    vi.useRealTimers();
  });

  it('keeps an unavailable status distinct from a backend FAILED job', async () => {
    vi.mocked(getShipmentImportJob).mockRejectedValueOnce(new ApiError('Job not found', 404));
    open(forwarder, '/freight-forwarder/import/42');
    expect(await screen.findByRole('alert')).toHaveTextContent('This import job is unavailable.');
    expect(screen.getByRole('button', { name: 'Retry status check' })).toBeInTheDocument();
  });

  it('shows a backend FAILED job with the template link and no row-error request', async () => {
    vi.mocked(getShipmentImportJob).mockResolvedValue({ ...job, status: 'FAILED', failureMessage: 'Invalid file format. Please download and use the provided CSV template.' });
    open(forwarder, '/freight-forwarder/import/42');
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid file format.');
    expect(getShipmentImportErrors).not.toHaveBeenCalled();
    expect(screen.getAllByRole('link', { name: 'Download CSV Template' })).not.toHaveLength(0);
  });

  it('shows complete all-success imports without fetching errors', async () => {
    vi.mocked(getShipmentImportJob).mockResolvedValue({ ...job, status: 'COMPLETED', totalRows: 2, processedRows: 2, importedCount: 2, completedAt: '2026-10-10T00:02:00Z' });
    open(forwarder, '/freight-forwarder/import/42');
    expect(await screen.findByText('2 shipments imported successfully.')).toBeInTheDocument();
    expect(getShipmentImportErrors).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: 'Back to Shipments' })).toHaveAttribute('href', '/freight-forwarder');
  });

  it('shows upload API validation and permission failures without navigating', async () => {
    open();
    const file = new File(['row'], 'shipments.csv', { type: 'text/csv' });
    const user = userEvent.setup();
    await user.upload(await screen.findByLabelText('CSV file'), file);
    vi.mocked(submitShipmentImport).mockRejectedValueOnce(new ApiError('CSV upload must not exceed 5 MB', 400));
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('CSV upload must not exceed 5 MB');

    vi.mocked(submitShipmentImport).mockRejectedValueOnce(new ApiError('Forbidden', 403));
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to import shipments.');
  });

  it('ends the session when a job-status request returns 401', async () => {
    vi.mocked(getShipmentImportJob).mockRejectedValueOnce(new ApiError('Your session has ended. Log in again.', 401));
    open(forwarder, '/freight-forwarder/import/42');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login'));
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });
});
