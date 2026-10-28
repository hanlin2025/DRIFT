import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { getShipmentHistory, type ShipmentHistory, type ShipmentHistoryEntry } from './api';
import { EARLIER_CHANGES_UNRECORDED, HISTORY_LOAD_FAILED, NO_CHANGES, ShipmentChangeHistory } from './ShipmentChangeHistory';
import { formatWhen } from './ShipmentForm';

vi.mock('./api', () => ({ getShipmentHistory: vi.fn() }));

const created: ShipmentHistoryEntry = {
  id: 1,
  changeType: 'CREATED',
  changedBy: { id: 3, fullName: 'Alice Tan' },
  recordedAt: '2026-10-20T01:00:00Z',
  changes: [],
};

const updated: ShipmentHistoryEntry = {
  id: 2,
  changeType: 'UPDATED',
  changedBy: { id: 4, fullName: 'Ben Ong' },
  recordedAt: '2026-10-21T04:52:22Z',
  changes: [
    { field: 'plannedFeederDepartureAt', previousValue: '2026-10-16T04:00:00Z', newValue: '2026-10-17T01:30:00Z' },
    { field: 'motherVesselImo', previousValue: null, newValue: '9387421' },
    { field: 'feederVesselOperator', previousValue: 'Strait Feeders', newValue: '' },
  ],
};

const linked: ShipmentHistoryEntry = {
  id: 3,
  changeType: 'IMPORTER_LINKED',
  changedBy: { id: 3, fullName: 'Alice Tan' },
  recordedAt: '2026-10-22T02:00:00Z',
  changes: [{ field: 'importer', previousValue: null, newValue: 'Straits Fresh Imports' }],
};

const archived: ShipmentHistoryEntry = {
  id: 4,
  changeType: 'ARCHIVED',
  changedBy: { id: 4, fullName: 'Ben Ong' },
  recordedAt: '2026-10-23T03:00:00Z',
  changes: [{ field: 'status', previousValue: 'ACTIVE', newValue: 'ARCHIVED' }],
};

function history(entries: ShipmentHistoryEntry[], earlierChangesUnrecorded = false): ShipmentHistory {
  return { shipmentId: 7, earlierChangesUnrecorded, entries };
}

function renderHistory({ reloadKey = 0, onSessionEnded = vi.fn() } = {}) {
  const view = render(<ShipmentChangeHistory token="session-token" shipmentId="7" reloadKey={reloadKey} onSessionEnded={onSessionEnded} />);
  return { ...view, onSessionEnded };
}

function entries() {
  return within(screen.getByRole('list', { name: 'Recorded changes' })).getAllByRole('listitem');
}

beforeEach(() => {
  vi.mocked(getShipmentHistory).mockReset();
});

describe('shipment change history', () => {
  it('lists the newest change first and the creation last', async () => {
    vi.mocked(getShipmentHistory).mockResolvedValue(history([archived, linked, updated, created]));

    renderHistory();

    expect(await screen.findByRole('list', { name: 'Recorded changes' })).toBeInTheDocument();
    expect(getShipmentHistory).toHaveBeenCalledWith('session-token', '7');
    expect(entries().map(entry => entry.querySelector('strong')?.textContent))
      .toEqual(['Archived', 'Importer linked', 'Updated', 'Created']);
    expect(screen.queryByText(NO_CHANGES)).not.toBeInTheDocument();
  });

  it('shows each changed field by its form label with readable before and after values', async () => {
    vi.mocked(getShipmentHistory).mockResolvedValue(history([archived, linked, updated, created]));

    renderHistory();

    const [archiveEntry, linkEntry, updateEntry] = (await screen.findAllByRole('listitem'));
    const departure = within(updateEntry).getByText('Planned feeder-vessel departure').parentElement!;
    expect(within(departure).getByText(formatWhen('2026-10-16T04:00:00Z'))).toHaveAttribute('datetime', '2026-10-16T04:00:00Z');
    expect(within(departure).getByText(formatWhen('2026-10-17T01:30:00Z'))).toHaveAttribute('datetime', '2026-10-17T01:30:00Z');

    const imo = within(updateEntry).getByText('Mother IMO').parentElement!;
    expect(within(imo).getByText('Not recorded')).toHaveClass('is-missing');
    expect(within(imo).getByText('9387421')).toBeInTheDocument();

    const operator = within(updateEntry).getByText('Feeder operator').parentElement!;
    expect(within(operator).getByText('Strait Feeders')).toBeInTheDocument();
    expect(within(operator).getByText('Not recorded')).toHaveClass('is-missing');

    const importer = within(linkEntry).getByText('Importer').parentElement!;
    expect(within(importer).getByText('Not recorded')).toBeInTheDocument();
    expect(within(importer).getByText('Straits Fresh Imports')).toBeInTheDocument();

    const status = within(archiveEntry).getByText('Status').parentElement!;
    expect(within(status).getByText('Active')).toBeInTheDocument();
    expect(within(status).getByText('Archived')).toBeInTheDocument();
  });

  it('shows who made each change and when', async () => {
    vi.mocked(getShipmentHistory).mockResolvedValue(history([updated, created]));

    renderHistory();

    const [updateEntry, createEntry] = await screen.findAllByRole('listitem');
    expect(updateEntry).toHaveTextContent(`Updated by Ben Ong · ${formatWhen('2026-10-21T04:52:22Z')}`);
    expect(within(updateEntry).getByText(formatWhen('2026-10-21T04:52:22Z'))).toHaveAttribute('datetime', '2026-10-21T04:52:22Z');
    expect(createEntry).toHaveTextContent(`Created by Alice Tan · ${formatWhen('2026-10-20T01:00:00Z')}`);
  });

  it('names an importer replacement and an unlink apart from a first link', async () => {
    const replaced = { ...linked, id: 5, changes: [{ field: 'importer', previousValue: 'Straits Fresh Imports', newValue: 'Harbour Foods' }] };
    const unlinked = { ...linked, id: 6, changes: [{ field: 'importer', previousValue: 'Harbour Foods', newValue: null }] };
    vi.mocked(getShipmentHistory).mockResolvedValue(history([unlinked, replaced, linked, created]));

    renderHistory();

    await screen.findByRole('list', { name: 'Recorded changes' });
    expect(entries().map(entry => entry.querySelector('strong')?.textContent))
      .toEqual(['Importer unlinked', 'Importer changed', 'Importer linked', 'Created']);
  });

  it('says there are no changes yet when only the creation is recorded', async () => {
    vi.mocked(getShipmentHistory).mockResolvedValue(history([created]));

    renderHistory();

    expect(await screen.findByText(NO_CHANGES)).toBeInTheDocument();
    expect(entries()).toHaveLength(1);
    expect(screen.queryByText(EARLIER_CHANGES_UNRECORDED)).not.toBeInTheDocument();
  });

  it('notes that earlier changes were not recorded', async () => {
    vi.mocked(getShipmentHistory).mockResolvedValue(history([updated, created], true));

    renderHistory();

    expect(await screen.findByText(EARLIER_CHANGES_UNRECORDED)).toBeInTheDocument();
    expect(screen.queryByText(NO_CHANGES)).not.toBeInTheDocument();
  });

  it('does not say there are no changes yet when the only recorded event is a backfilled creation', async () => {
    vi.mocked(getShipmentHistory).mockResolvedValue(history([created], true));

    renderHistory();

    expect(await screen.findByText(EARLIER_CHANGES_UNRECORDED)).toBeInTheDocument();
    expect(screen.queryByText(NO_CHANGES)).not.toBeInTheDocument();
    expect(entries()).toHaveLength(1);
    expect(entries()[0]).toHaveTextContent('Created');
  });

  it('shows a clear error when the history cannot be loaded and loads it again on Try again', async () => {
    vi.mocked(getShipmentHistory)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce(history([updated, created]));
    const user = userEvent.setup();

    renderHistory();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(HISTORY_LOAD_FAILED);
    expect(alert).toHaveTextContent('We could not reach DRIFT.');
    expect(screen.queryByRole('list', { name: 'Recorded changes' })).not.toBeInTheDocument();

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('list', { name: 'Recorded changes' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(getShipmentHistory).toHaveBeenCalledTimes(2);
  });

  it('ends the session on a 401 instead of showing an error', async () => {
    vi.mocked(getShipmentHistory).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));

    const { onSessionEnded } = renderHistory();

    await vi.waitFor(() => expect(onSessionEnded).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('loads the history again when the reload key changes', async () => {
    vi.mocked(getShipmentHistory)
      .mockResolvedValueOnce(history([created]))
      .mockResolvedValueOnce(history([updated, created]));

    const { rerender, onSessionEnded } = renderHistory();
    expect(await screen.findByText(NO_CHANGES)).toBeInTheDocument();

    rerender(<ShipmentChangeHistory token="session-token" shipmentId="7" reloadKey={1} onSessionEnded={onSessionEnded} />);

    expect(await screen.findByText('Updated')).toBeInTheDocument();
    expect(screen.queryByText(NO_CHANGES)).not.toBeInTheDocument();
    expect(getShipmentHistory).toHaveBeenCalledTimes(2);
  });
});
