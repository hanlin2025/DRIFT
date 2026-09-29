import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { type Shipment } from '../shipment/api';
import type { Session } from '../session/session';
import { ShipmentList } from './ShipmentList';
import { WorkspacePage } from './WorkspaceRoute';

const newer: Shipment = {
  id: 2,
  shipmentReference: 'HBL-NEWER',
  origin: 'Shanghai, CN',
  destination: 'Jakarta, ID',
  motherVessel: 'MV Pacific Horizon',
  plannedMotherArrivalAt: '2026-10-04T00:00:00Z',
  feederVessel: 'MV Strait Runner',
  plannedFeederDepartureAt: '2026-10-05T00:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
};
const older: Shipment = {
  id: 1,
  shipmentReference: 'HBL-OLDER',
  origin: 'Busan, KR',
  destination: 'Singapore, SG',
  motherVessel: 'MV Northern Light',
  plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
  feederVessel: 'MV Harbour Link',
  plannedFeederDepartureAt: '2026-10-03T00:00:00Z',
  createdAt: '2026-09-28T04:00:00Z',
};

const account = (role: Session['role']): Session => ({
  token: 'session-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 4,
  fullName: 'Alice Tan',
  email: 'alice@example.com',
  role,
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
});

describe('shipment list', () => {
  it('maps each shipment field to its column and keeps the supplied order', () => {
    render(<ShipmentList shipments={[newer, older]} />);
    expect(screen.getAllByRole('columnheader').map(header => header.textContent)).toEqual([
      'Reference', 'Origin', 'Destination', 'Mother vessel', 'Feeder vessel',
    ]);
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'HBL-NEWER', 'Shanghai, CN', 'Jakarta, ID', 'MV Pacific Horizon', 'MV Strait Runner',
    ]);
    expect(within(rows[1]).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'HBL-OLDER', 'Busan, KR', 'Singapore, SG', 'MV Northern Light', 'MV Harbour Link',
    ]);
  });

  it('renders no shipment rows for an empty list', () => {
    render(<ShipmentList shipments={[]} />);
    expect(screen.getAllByRole('columnheader')).toHaveLength(5);
    expect(screen.queryAllByRole('cell')).toHaveLength(0);
  });
});

describe('workspace shipment list', () => {
  it('shows the supplied shipments to a freight forwarder and keeps the registration form', () => {
    render(<WorkspacePage account={account('FREIGHT_FORWARDER')} onSignOut={() => {}} onSessionEnded={() => {}} shipments={[newer]} />);
    expect(screen.getByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'HBL-NEWER' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
  });

  it('does not show a shipment table to an importer', () => {
    render(<WorkspacePage account={account('IMPORTER')} onSignOut={() => {}} onSessionEnded={() => {}} shipments={[newer]} />);
    expect(screen.getByRole('heading', { name: 'Shipment overview' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a shipment' })).not.toBeInTheDocument();
  });

  it('keeps the table hidden when the live route does not supply shipments', () => {
    render(<WorkspacePage account={account('FREIGHT_FORWARDER')} onSignOut={() => {}} onSessionEnded={() => {}} />);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Register a shipment' })).toBeInTheDocument();
  });
});
