import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { getShipment, linkShipmentImporter, listImporterOrganisations, type Shipment } from './api';
import { ImporterLink } from './ImporterLink';

vi.mock('./api', () => ({
  getShipment: vi.fn(),
  listImporterOrganisations: vi.fn(),
  linkShipmentImporter: vi.fn(),
}));

const linked: Shipment = {
  id: 7,
  shipmentReference: 'HL-1001',
  origin: 'Singapore',
  destination: 'Jakarta',
  transshipmentPort: 'Singapore',
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
  connectionWindow: null,
  motherVesselPosition: null,
  feederVesselPosition: null,
  importerOrganisation: { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' },
};

const straits = { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' };
const northwind = { id: 3, code: 'NORTHWIND_DEMO', name: 'Northwind Imports' };

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(listImporterOrganisations).mockResolvedValue([straits, northwind]);
});

describe('importer link', () => {
  it('hides the editor from an importer and shows the linked organisation', async () => {
    vi.mocked(getShipment).mockResolvedValue(linked);
    render(<ImporterLink token="importer-token" shipmentId="7" role="IMPORTER" onSessionEnded={vi.fn()} />);
    expect(await screen.findByText('Linked importer: Straits Fresh Imports (Demo)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Save importer link/ })).not.toBeInTheDocument();
    expect(listImporterOrganisations).not.toHaveBeenCalled();
  });

  it('hides the importer link when the shipment is not linked and the viewer is an importer', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...linked, importerOrganisation: null });
    render(<ImporterLink token="importer-token" shipmentId="7" role="IMPORTER" onSessionEnded={vi.fn()} />);
    expect(await screen.findByText('Loading importer link...', {}, { timeout: 1 }).catch(() => null)).toBeNull();
    await vi.waitFor(() => expect(getShipment).toHaveBeenCalled());
    expect(screen.queryByText(/Linked importer/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Save importer link/ })).not.toBeInTheDocument();
  });

  it('moves the link to another organisation and can remove it', async () => {
    vi.mocked(getShipment).mockResolvedValue(linked);
    vi.mocked(linkShipmentImporter)
      .mockResolvedValueOnce({ ...linked, importerOrganisation: northwind })
      .mockResolvedValueOnce({ ...linked, importerOrganisation: null });
    const user = userEvent.setup();
    render(<ImporterLink token="forwarder-token" shipmentId="7" role="FREIGHT_FORWARDER" onSessionEnded={vi.fn()} />);
    expect(await screen.findByText('This shipment is linked to Straits Fresh Imports (Demo).')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Importer organisation'), 'Northwind Imports');
    await user.click(screen.getByRole('button', { name: /Save importer link/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('Shipment linked to Northwind Imports.');
    expect(linkShipmentImporter).toHaveBeenCalledWith('forwarder-token', '7', 3);
    await user.click(screen.getByRole('button', { name: 'Remove importer link' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Importer link removed.');
    expect(linkShipmentImporter).toHaveBeenLastCalledWith('forwarder-token', '7', null);
    expect(screen.queryByRole('button', { name: 'Remove importer link' })).not.toBeInTheDocument();
  });

  it('shows the API refusal when the caller cannot change the link', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...linked, importerOrganisation: null });
    vi.mocked(linkShipmentImporter).mockRejectedValue(new ApiError('Only a freight forwarder in the shipment\'s company can link it to an importer', 403));
    const user = userEvent.setup();
    render(<ImporterLink token="forwarder-token" shipmentId="7" role="FREIGHT_FORWARDER" onSessionEnded={vi.fn()} />);
    await user.click(await screen.findByRole('button', { name: /Save importer link/ }));
    expect(await screen.findByText('Only a freight forwarder in the shipment\'s company can link it to an importer')).toBeInTheDocument();
  });

  it('does not offer a link for a shipment that cannot be seen', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    render(<ImporterLink token="forwarder-token" shipmentId="99" role="FREIGHT_FORWARDER" onSessionEnded={vi.fn()} />);
    await vi.waitFor(() => expect(getShipment).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: /Save importer link/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
