import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { getCdg201ArrivalEvidence, getShipment, getShipmentHistory, getShipmentTracking, getVesselTrack, updateTransshipmentArrivalDeadline, type Shipment } from './api';
import { ShipmentDetail, TRACKING_POLL_MS } from './ShipmentDetail';
import { toOffsetDateTime } from './validation';

vi.mock('./api', () => ({
  getShipment: vi.fn(),
  getShipmentTracking: vi.fn(),
  getVesselTrack: vi.fn(),
  updateTransshipmentArrivalDeadline: vi.fn(),
  getShipmentHistory: vi.fn(),
  getCdg201ArrivalEvidence: vi.fn(),
}));

const EXACT = '2026-10-28T16:00:00.123456789Z';
const CONFLICT = 'The transshipment port changed. Refresh the shipment and confirm the deadline for the current port.';

function shipment(overrides: Partial<Shipment> = {}): Shipment {
  return {
    id: 7,
    shipmentReference: 'HL-1001',
    origin: 'Shanghai',
    destination: 'Jakarta',
    transshipmentPort: 'Singapore',
    motherVessel: 'Ever Steady',
    plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
    feederVessel: 'Straits Feeder',
    plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
    createdAt: '2026-09-29T04:00:00Z',
    version: 3,
    companyId: 1,
    connectionWindow: null,
    remainingConnectionWindow: null,
    risk: null,
    status: 'ACTIVE',
    motherVesselPosition: null,
    feederVesselPosition: null,
    importer: { id: 8, code: 'STRAITS', name: 'Straits Fresh' },
    arrivalComparison: null,
    ...overrides,
  };
}

function deadline() {
  const fact = screen.getByText('Mother-vessel arrival deadline').closest('div');
  if (!fact) throw new Error('Missing deadline fact');
  return within(fact as HTMLElement);
}

function renderDetail(current: Shipment, viewerCompanyId: number | null = 8) {
  return render(
    <MemoryRouter>
      <ShipmentDetail token="importer-token" shipmentId="7" basePath="/importer" viewerCompanyId={viewerCompanyId} onSessionEnded={() => {}} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getShipmentHistory).mockResolvedValue({ shipmentId: 7, earlierChangesUnrecorded: false, entries: [] });
  vi.mocked(getCdg201ArrivalEvidence).mockResolvedValue(null);
  vi.mocked(getShipmentTracking).mockResolvedValue({
    motherVesselName: 'Ever Steady', motherVessel: null, motherVesselNameWarning: null,
    feederVesselName: 'Straits Feeder', feederVessel: null, feederVesselNameWarning: null,
  });
  vi.mocked(getVesselTrack).mockResolvedValue({
    vessel: 'MOTHER', vesselName: 'Ever Steady', mmsi: '563001234', windowHours: 24,
    from: '2026-10-04T03:00:00Z', to: '2026-10-05T03:00:00Z', slowdownThresholdKnots: 3,
    pointLimit: 500, truncated: false, points: [],
  });
});

describe('transshipment arrival deadline on the shipment page', () => {
  it('lets the linked importer save, replace, and clear without using shipment editing', async () => {
    const user = userEvent.setup();
    const saved = shipment({ transshipmentArrivalDeadline: { deadline: '2026-10-28T16:00:00Z', transshipmentPort: 'Singapore', applicable: true } });
    const replaced = shipment({ transshipmentArrivalDeadline: { deadline: '2026-10-29T16:00:00Z', transshipmentPort: 'Singapore', applicable: true } });
    vi.mocked(getShipment).mockResolvedValue(shipment());
    vi.mocked(updateTransshipmentArrivalDeadline)
      .mockResolvedValueOnce(saved)
      .mockResolvedValueOnce(replaced)
      .mockResolvedValueOnce(shipment());
    renderDetail(shipment());
    expect(await screen.findByText('Not recorded')).toBeInTheDocument();
    expect(screen.getByText(/not the final delivery time/)).toBeInTheDocument();
    expect(screen.getByText('Confirming port: Singapore')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit shipment' })).toBeInTheDocument();

    const input = screen.getByLabelText('Deadline');
    await user.type(input, '2026-10-29T00:00:00');
    await user.click(screen.getByRole('button', { name: 'Save deadline' }));
    expect(updateTransshipmentArrivalDeadline).toHaveBeenCalledWith('importer-token', 7, {
      transshipmentArrivalDeadline: toOffsetDateTime('2026-10-29T00:00:00'),
      expectedTransshipmentPort: 'Singapore',
    });
    expect(deadline().getByRole('time')).toHaveAttribute('dateTime', '2026-10-28T16:00:00Z');

    await user.clear(input);
    await user.type(input, '2026-10-30T00:00:00');
    await user.click(screen.getByRole('button', { name: 'Update deadline' }));
    expect(deadline().getByRole('time')).toHaveAttribute('dateTime', '2026-10-29T16:00:00Z');

    await user.click(screen.getByRole('button', { name: 'Clear deadline' }));
    expect(updateTransshipmentArrivalDeadline).toHaveBeenLastCalledWith('importer-token', 7, {
      transshipmentArrivalDeadline: null,
      expectedTransshipmentPort: 'Singapore',
    });
    expect(screen.getByText('Not recorded')).toBeInTheDocument();
  });

  it('requires a present matching importer company and an active shipment', async () => {
    const recorded = shipment({ transshipmentArrivalDeadline: { deadline: EXACT, transshipmentPort: 'Singapore', applicable: true } });
    const cases: Array<[number | null, Shipment]> = [
      [null, recorded],
      [8, shipment({ importer: null })],
      [3, recorded],
      [8, shipment({ status: 'ARCHIVED', transshipmentArrivalDeadline: recorded.transshipmentArrivalDeadline })],
      [8, shipment({ status: null, transshipmentArrivalDeadline: recorded.transshipmentArrivalDeadline })],
    ];
    for (const [viewer, current] of cases) {
      vi.mocked(getShipment).mockResolvedValue(current);
      const view = renderDetail(current, viewer);
      expect(await screen.findByRole('article', { name: 'Shipment HL-1001' })).toBeInTheDocument();
      expect(screen.queryByLabelText('Deadline')).not.toBeInTheDocument();
      view.unmount();
    }
    expect(screen.queryByText('2026-10-28T16:00:00.123456789Z')).not.toBeInTheDocument();
  });

  it('reconfirms an unchanged instant exactly and converts a changed local time', async () => {
    const user = userEvent.setup();
    const current = shipment({
      transshipmentPort: 'Tanjung Pelepas',
      transshipmentArrivalDeadline: { deadline: EXACT, transshipmentPort: 'Singapore', applicable: false },
    });
    vi.mocked(getShipment).mockResolvedValue(current);
    vi.mocked(updateTransshipmentArrivalDeadline).mockResolvedValue(shipment({
      transshipmentPort: 'Tanjung Pelepas',
      transshipmentArrivalDeadline: { deadline: EXACT, transshipmentPort: 'Tanjung Pelepas', applicable: true },
    }));
    renderDetail(current);
    expect(await screen.findByText(/does not apply to the current port/)).toBeInTheDocument();
    expect(screen.getByText('Confirming port: Tanjung Pelepas')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirm for Tanjung Pelepas' }));
    expect(updateTransshipmentArrivalDeadline).toHaveBeenCalledWith('importer-token', 7, {
      transshipmentArrivalDeadline: EXACT,
      expectedTransshipmentPort: 'Tanjung Pelepas',
    });

    const changed = '2026-11-02T09:15:45';
    await user.clear(screen.getByLabelText('Deadline'));
    await user.type(screen.getByLabelText('Deadline'), changed);
    await user.click(screen.getByRole('button', { name: 'Update deadline' }));
    expect(updateTransshipmentArrivalDeadline).toHaveBeenLastCalledWith('importer-token', 7, {
      transshipmentArrivalDeadline: toOffsetDateTime(changed),
      expectedTransshipmentPort: 'Tanjung Pelepas',
    });
    expect(toOffsetDateTime(changed)).not.toBe(EXACT);
  });

  it('keeps the captured port during a background refresh', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      const current = shipment();
      vi.mocked(getShipment)
        .mockResolvedValueOnce(current)
        .mockResolvedValue({ ...current, transshipmentPort: 'Tanjung Pelepas' });
      renderDetail(current);
      expect(await screen.findByText('Confirming port: Singapore')).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
      expect(screen.getByText('Confirming port: Singapore')).toBeInTheDocument();
      expect(screen.getByText(/Refresh the shipment before saving/)).toBeInTheDocument();
      expect(updateTransshipmentArrivalDeadline).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps a failed entry until the user refreshes and saves again', async () => {
    const user = userEvent.setup();
    const current = shipment();
    const moved = shipment({ transshipmentPort: 'Tanjung Pelepas' });
    vi.mocked(getShipment).mockResolvedValueOnce(current).mockResolvedValue(moved);
    vi.mocked(updateTransshipmentArrivalDeadline)
      .mockRejectedValueOnce(new ApiError(CONFLICT, 409))
      .mockRejectedValueOnce(new ApiError('Shipment information is missing or invalid', 400, { transshipmentArrivalDeadline: 'The deadline must include a date, time, and timezone offset.' }))
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce(moved);
    renderDetail(current);
    const input = await screen.findByLabelText('Deadline');
    await user.type(input, '2026-10-29T00:00:00');
    await user.click(screen.getByRole('button', { name: 'Save deadline' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(CONFLICT);
    expect(input).toHaveValue('2026-10-29T00:00');
    expect(updateTransshipmentArrivalDeadline).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Refresh shipment' }));
    expect(await screen.findByText('Confirming port: Tanjung Pelepas')).toBeInTheDocument();
    expect(input).toHaveValue('2026-10-29T00:00');
    expect(updateTransshipmentArrivalDeadline).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Save deadline' }));
    expect(await screen.findByText('The deadline must include a date, time, and timezone offset.')).toBeInTheDocument();
    expect(input).toHaveValue('2026-10-29T00:00');
    await user.click(screen.getByRole('button', { name: 'Save deadline' }));
    expect(await screen.findByText('We could not reach DRIFT. Check your connection and try again.')).toBeInTheDocument();
    expect(updateTransshipmentArrivalDeadline).toHaveBeenCalledTimes(3);
    expect(vi.mocked(updateTransshipmentArrivalDeadline).mock.calls[2][2]).toMatchObject({
      expectedTransshipmentPort: 'Tanjung Pelepas',
    });
  });

  it('discards the draft when the importer or access changes', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const user = userEvent.setup();
    try {
      const current = shipment();
      vi.mocked(getShipment)
        .mockResolvedValueOnce(current)
        .mockResolvedValueOnce(shipment({
          importer: { id: 9, code: 'OTHER', name: 'Other Imports' },
          transshipmentArrivalDeadline: { deadline: '2026-12-01T00:00:00Z', transshipmentPort: 'Singapore', applicable: true },
        }));
      renderDetail(current);
      const input = await screen.findByLabelText('Deadline');
      await user.type(input, '2026-10-29T00:00:00');
      await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
      expect(screen.queryByLabelText('Deadline')).not.toBeInTheDocument();
      expect(screen.queryByDisplayValue('2026-10-29T00:00')).not.toBeInTheDocument();
      expect(deadline().getByRole('time')).toHaveAttribute('dateTime', '2026-12-01T00:00:00Z');

      vi.mocked(getShipment).mockRejectedValueOnce(new ApiError('You do not have access to this shipment', 403));
      await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
      expect(screen.queryByRole('time')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Deadline')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
