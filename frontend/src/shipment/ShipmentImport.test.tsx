import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { downloadImportErrors, downloadShipmentTemplate, importShipments } from './api';
import { ShipmentImport } from './ShipmentImport';

vi.mock('./api', () => ({
  downloadShipmentTemplate: vi.fn(),
  downloadImportErrors: vi.fn(),
  importShipments: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(downloadShipmentTemplate).mockResolvedValue(new Blob(['Tracking/BL No.\n']));
  vi.mocked(downloadImportErrors).mockResolvedValue(new Blob(['Row,Reason\n']));
  URL.createObjectURL = vi.fn(() => 'blob:shipment-import');
  URL.revokeObjectURL = vi.fn();
});

describe('shipment CSV import', () => {
  it('downloads the template and reports a partial import with an error file', async () => {
    vi.mocked(importShipments).mockResolvedValue({ id: 4, imported: 2, failed: 1, summary: '2 imported, 1 failed' });
    const onImported = vi.fn();
    const user = userEvent.setup();
    render(<ShipmentImport token="session-token" onSessionEnded={vi.fn()} onImported={onImported} />);
    await user.click(screen.getByRole('button', { name: 'Download CSV template' }));
    expect(downloadShipmentTemplate).toHaveBeenCalledWith('session-token');

    const file = new File(['Tracking/BL No.\n'], 'shipments.csv', { type: 'text/csv' });
    await user.upload(screen.getByLabelText('CSV file'), file);
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(await screen.findByRole('status')).toHaveTextContent('2 imported, 1 failed');
    expect(importShipments).toHaveBeenCalledWith('session-token', file);
    expect(onImported).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Download error report' }));
    expect(downloadImportErrors).toHaveBeenCalledWith('session-token', 4);
  });

  it('asks for a file before calling the API and shows the template error', async () => {
    vi.mocked(importShipments).mockRejectedValue(new ApiError('Invalid file format. Please download and use the provided CSV template.', 400));
    const user = userEvent.setup();
    render(<ShipmentImport token="session-token" onSessionEnded={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a CSV file to import.');
    expect(importShipments).not.toHaveBeenCalled();

    const file = new File(['nope'], 'shipments.csv', { type: 'text/csv' });
    await user.upload(screen.getByLabelText('CSV file'), file);
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid file format. Please download and use the provided CSV template.');
  });

  it('reports a complete import without an error file and ends the session when the token is rejected', async () => {
    vi.mocked(importShipments).mockResolvedValueOnce({ id: 8, imported: 1, failed: 0, summary: '1 shipment imported successfully' });
    const user = userEvent.setup();
    const { rerender } = render(<ShipmentImport token="session-token" onSessionEnded={vi.fn()} />);
    await user.upload(screen.getByLabelText('CSV file'), new File(['ok'], 'shipments.csv', { type: 'text/csv' }));
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(await screen.findByRole('status')).toHaveTextContent('1 shipment imported successfully');
    expect(screen.queryByRole('button', { name: 'Download error report' })).not.toBeInTheDocument();

    const onSessionEnded = vi.fn();
    vi.mocked(importShipments).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    rerender(<ShipmentImport token="session-token" onSessionEnded={onSessionEnded} />);
    await user.upload(screen.getByLabelText('CSV file'), new File(['ok'], 'shipments.csv', { type: 'text/csv' }));
    await user.click(screen.getByRole('button', { name: 'Import shipments' }));
    expect(onSessionEnded).toHaveBeenCalled();
  });
});
