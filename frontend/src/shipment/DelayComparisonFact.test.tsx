import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { getShipment, getShipmentTracking, getVesselTrack, updateTransshipmentArrivalDeadline, type ArrivalComparison, type DelayComparison, type Shipment } from './api';
import { DelayComparisonFact } from './DelayComparisonFact';
import { ShipmentDetail, TRACKING_POLL_MS } from './ShipmentDetail';
import { formatWhen } from './ShipmentForm';

vi.mock('./api', () => ({
  getShipment: vi.fn(),
  getShipmentTracking: vi.fn(),
  getVesselTrack: vi.fn(),
  updateTransshipmentArrivalDeadline: vi.fn(),
}));

const NO_IMPACT = 'No predicted impact on the recorded mother-vessel transshipment-arrival deadline.';
const EXCEEDS = 'The predicted arrival exceeds the recorded mother-vessel transshipment-arrival deadline.';
const INCOMPATIBLE = 'The recorded deadline is for a different transshipment port.';
const MISSING_PREDICTION = 'The mother-vessel transshipment-arrival estimate is unavailable.';
const MISSING_CONSTRAINT = 'No mother-vessel transshipment-arrival deadline is recorded for the current importer.';
const STALE = 'The mother-vessel transshipment-arrival estimate uses an AIS position received more than six hours ago.';
const PREDICTION_EXPLANATION = 'No AIS position has been received for the mother vessel.';
const ESTIMATE = '2026-10-28T15:00:00Z';
const DEADLINE = '2026-10-28T16:00:00Z';
const LATER_DEADLINE = '2026-10-28T18:00:00Z';

function comparison(overrides: Partial<DelayComparison> = {}): DelayComparison {
  return {
    outcome: 'NO_PREDICTED_IMPACT',
    reason: null,
    message: NO_IMPACT,
    estimatedArrivalAt: ESTIMATE,
    deadline: DEADLINE,
    recordedTransshipmentPort: 'Singapore',
    ...overrides,
  };
}

function unavailableArrival(): ArrivalComparison {
  return {
    estimatedArrivalAt: null,
    limitation: null,
    speedOverGroundKnots: null,
    classification: null,
    difference: null,
    differenceSeconds: null,
    differenceNanos: null,
    lastReceivedAt: null,
    possiblyOutdated: null,
    unavailableReason: { code: 'NO_POSITION', message: PREDICTION_EXPLANATION },
  };
}

function onSchedule(): ArrivalComparison {
  return {
    estimatedArrivalAt: ESTIMATE,
    limitation: 'Approximate arrival — based on straight-line distance and current speed.',
    speedOverGroundKnots: 12.5,
    classification: 'ON_SCHEDULE',
    difference: 'On schedule',
    differenceSeconds: 0,
    differenceNanos: 0,
    lastReceivedAt: '2026-10-28T12:00:00Z',
    possiblyOutdated: false,
    unavailableReason: null,
  };
}

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
    risk: { level: 'LOW', explanation: 'At least 24 hours to transfer cargo between vessels.' },
    status: 'ACTIVE',
    motherVesselPosition: null,
    feederVesselPosition: null,
    importer: { id: 8, code: 'STRAITS', name: 'Straits Fresh' },
    arrivalComparison: onSchedule(),
    transshipmentArrivalDeadline: { deadline: DEADLINE, transshipmentPort: 'Singapore', applicable: true },
    delayComparison: comparison(),
    ...overrides,
  };
}

function assessment() {
  const fact = screen.getByText('Deadline assessment').closest('div');
  if (!fact) throw new Error('Missing deadline assessment');
  return within(fact as HTMLElement);
}

function labelled(label: string) {
  const row = assessment().getByText(label, { exact: true, selector: 'dt' }).closest('div');
  if (!row) throw new Error(`Missing ${label}`);
  return within(row as HTMLElement);
}

function renderFact(current: Shipment) {
  return render(<dl><DelayComparisonFact shipment={current} /></dl>);
}

function renderDetail(current: Shipment, viewerCompanyId: number | null = 8) {
  return render(
    <MemoryRouter>
      <ShipmentDetail token="importer-token" shipmentId={String(current.id)} basePath="/importer" viewerCompanyId={viewerCompanyId} onSessionEnded={() => {}} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
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

describe('deadline assessment fact', () => {
  it('shows both completed outcomes with their inputs and keeps the plan and risk separate', () => {
    const { rerender } = renderFact(shipment());
    expect(assessment().getByText(NO_IMPACT).tagName).toBe('P');
    expect(labelled('Predicted transshipment arrival').getByRole('time')).toHaveAttribute('dateTime', ESTIMATE);
    expect(labelled('Predicted transshipment arrival').getByRole('time')).toHaveTextContent(formatWhen(ESTIMATE));
    expect(labelled('Recorded deadline').getByRole('time')).toHaveAttribute('dateTime', DEADLINE);
    expect(labelled('Recorded port').getByText('Singapore')).toBeInTheDocument();
    expect(assessment().getByText('Shown in your local time.')).toBeInTheDocument();
    expect(assessment().getByText(/separate from the planned-arrival comparison and from connection risk/)).toBeInTheDocument();
    expect(assessment().queryByText('On schedule')).not.toBeInTheDocument();
    expect(assessment().queryByText('Low')).not.toBeInTheDocument();

    rerender(<dl><DelayComparisonFact shipment={shipment({
      delayComparison: comparison({ outcome: 'EXCEEDS_DEADLINE', message: EXCEEDS, deadline: LATER_DEADLINE }),
    })} /></dl>);
    expect(assessment().getByText(EXCEEDS)).toBeInTheDocument();
    expect(assessment().queryByText(NO_IMPACT)).not.toBeInTheDocument();
    expect(labelled('Recorded deadline').getByRole('time')).toHaveAttribute('dateTime', LATER_DEADLINE);
  });

  it('shows an on-schedule plan beside an exceeded deadline without combining them', () => {
    render(
      <dl>
        <div>
          <dt>Comparison</dt>
          <dd>On schedule</dd>
        </div>
        <div>
          <dt>Risk</dt>
          <dd>Low At least 24 hours to transfer cargo between vessels.</dd>
        </div>
        <DelayComparisonFact shipment={shipment({
          delayComparison: comparison({ outcome: 'EXCEEDS_DEADLINE', message: EXCEEDS }),
        })} />
      </dl>,
    );
    expect(screen.getByText('On schedule')).toBeInTheDocument();
    expect(screen.getByText(/At least 24 hours/)).toBeInTheDocument();
    expect(assessment().getByText(EXCEEDS)).toBeInTheDocument();
    expect(assessment().queryByText('On schedule')).not.toBeInTheDocument();
  });

  it('explains every unavailable reason without presenting a completed comparison', () => {
    const { rerender } = renderFact(shipment({
      arrivalComparison: unavailableArrival(),
      delayComparison: comparison({
        outcome: null, reason: 'MISSING_PREDICTION', message: MISSING_PREDICTION, estimatedArrivalAt: null,
      }),
    }));
    expect(assessment().getByText(MISSING_PREDICTION).tagName).toBe('P');
    expect(labelled('Predicted transshipment arrival').getByText(`Unavailable. ${PREDICTION_EXPLANATION}`)).toBeInTheDocument();
    expect(labelled('Predicted transshipment arrival').queryByRole('time')).not.toBeInTheDocument();
    expect(labelled('Recorded deadline').getByRole('time')).toHaveAttribute('dateTime', DEADLINE);

    rerender(<dl><DelayComparisonFact shipment={shipment({
      delayComparison: comparison({
        outcome: null, reason: 'MISSING_PREDICTION', message: MISSING_PREDICTION,
        estimatedArrivalAt: null, deadline: null, recordedTransshipmentPort: null,
      }),
      arrivalComparison: null,
    })} /></dl>);
    expect(labelled('Predicted transshipment arrival').getByText('Unavailable')).toBeInTheDocument();
    expect(labelled('Recorded deadline').getByText('Not recorded')).toBeInTheDocument();
    expect(assessment().queryByText(NO_IMPACT)).not.toBeInTheDocument();

    rerender(<dl><DelayComparisonFact shipment={shipment({
      delayComparison: comparison({
        outcome: null, reason: 'MISSING_CONSTRAINT', message: MISSING_CONSTRAINT, deadline: null, recordedTransshipmentPort: null,
      }),
      transshipmentArrivalDeadline: null,
    })} /></dl>);
    expect(assessment().getByText(MISSING_CONSTRAINT)).toBeInTheDocument();
    expect(labelled('Recorded deadline').getByText('Not recorded')).toBeInTheDocument();
    expect(labelled('Recorded port').getByText('Not recorded')).toBeInTheDocument();
    expect(labelled('Predicted transshipment arrival').getByRole('time')).toHaveAttribute('dateTime', ESTIMATE);

    rerender(<dl><DelayComparisonFact shipment={shipment({
      delayComparison: comparison({ outcome: null, reason: 'STALE_PREDICTION', message: STALE }),
    })} /></dl>);
    expect(assessment().getByText('No deadline assessment was completed.')).toBeInTheDocument();
    expect(assessment().getByText(STALE)).toBeInTheDocument();
    expect(labelled('Predicted transshipment arrival, not assessed').getByRole('time')).toHaveAttribute('dateTime', ESTIMATE);
    expect(labelled('Recorded deadline, not assessed').getByRole('time')).toHaveAttribute('dateTime', DEADLINE);
    expect(assessment().queryByText('Predicted transshipment arrival', { exact: true, selector: 'dt' })).not.toBeInTheDocument();

    rerender(<dl><DelayComparisonFact shipment={shipment({
      transshipmentPort: 'Tanjung Pelepas',
      delayComparison: comparison({ outcome: null, reason: 'INCOMPATIBLE_MILESTONE', message: INCOMPATIBLE }),
    })} /></dl>);
    expect(assessment().getByText('No deadline assessment was completed.')).toBeInTheDocument();
    expect(assessment().getByText(INCOMPATIBLE)).toBeInTheDocument();
    expect(labelled('Recorded port').getByText('Singapore')).toBeInTheDocument();
    expect(labelled('Current transshipment port').getByText('Tanjung Pelepas')).toBeInTheDocument();
    expect(labelled('Predicted transshipment arrival, not assessed').getByRole('time')).toBeInTheDocument();
    expect(assessment().queryByText(NO_IMPACT)).not.toBeInTheDocument();
    expect(assessment().queryByText(EXCEEDS)).not.toBeInTheDocument();
  });

  it('renders nothing when the assessment is missing and does not claim no impact', () => {
    const { rerender } = renderFact(shipment({ delayComparison: null }));
    expect(screen.queryByText('Deadline assessment')).not.toBeInTheDocument();
    expect(screen.queryByText(NO_IMPACT)).not.toBeInTheDocument();
    rerender(<dl><DelayComparisonFact shipment={shipment({ delayComparison: undefined })} /></dl>);
    expect(screen.queryByText('Deadline assessment')).not.toBeInTheDocument();
  });
});

describe('deadline assessment on the shipment page', () => {
  it('replaces the assessment when a deadline save returns a new detail and keeps it after a conflict', async () => {
    const user = userEvent.setup();
    const saved = shipment();
    const exceeded = shipment({
      delayComparison: comparison({ outcome: 'EXCEEDS_DEADLINE', message: EXCEEDS, deadline: LATER_DEADLINE }),
      transshipmentArrivalDeadline: { deadline: LATER_DEADLINE, transshipmentPort: 'Singapore', applicable: true },
    });
    vi.mocked(getShipment).mockResolvedValue(saved);
    vi.mocked(updateTransshipmentArrivalDeadline)
      .mockResolvedValueOnce(exceeded)
      .mockRejectedValueOnce(new ApiError('The transshipment port changed. Refresh the shipment and confirm the deadline for the current port.', 409));
    renderDetail(saved);
    expect(await screen.findByText(NO_IMPACT)).toBeInTheDocument();
    expect(screen.getByText('On schedule')).toBeInTheDocument();
    expect(screen.getByText('Low')).toBeInTheDocument();

    const input = screen.getByLabelText('Deadline');
    await user.clear(input);
    await user.type(input, '2026-10-29T02:00:00');
    expect(screen.getByText(NO_IMPACT)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Update deadline' }));
    expect(await screen.findByText(EXCEEDS)).toBeInTheDocument();
    expect(screen.queryByText(NO_IMPACT)).not.toBeInTheDocument();
    expect(labelled('Recorded deadline').getByRole('time')).toHaveAttribute('dateTime', LATER_DEADLINE);

    const conflicted = screen.getByLabelText('Deadline');
    await user.clear(conflicted);
    await user.type(conflicted, '2026-11-01T02:00:00');
    await user.click(screen.getByRole('button', { name: 'Update deadline' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The transshipment port changed.');
    expect(screen.getByText(EXCEEDS)).toBeInTheDocument();
    expect(labelled('Recorded deadline').getByRole('time')).toHaveAttribute('dateTime', LATER_DEADLINE);
    expect(screen.getByLabelText('Deadline')).toHaveValue('2026-11-01T02:00');
  });

  it('replaces the assessment on refresh and removes it when access or context changes', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const user = userEvent.setup();
    try {
      const saved = shipment();
      const otherImporter = shipment({
        importer: { id: 9, code: 'OTHER', name: 'Other Imports' },
        delayComparison: comparison({ outcome: 'EXCEEDS_DEADLINE', message: EXCEEDS, deadline: LATER_DEADLINE }),
        transshipmentArrivalDeadline: { deadline: LATER_DEADLINE, transshipmentPort: 'Singapore', applicable: true },
      });
      vi.mocked(getShipment).mockResolvedValueOnce(saved).mockResolvedValueOnce(otherImporter);
      const view = renderDetail(saved);
      expect(await screen.findByText(NO_IMPACT)).toBeInTheDocument();
      const input = screen.getByLabelText('Deadline');
      await user.clear(input);
      await user.type(input, '2026-10-29T02:00:00');
      await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
      expect(screen.queryByText(NO_IMPACT)).not.toBeInTheDocument();
      expect(screen.getByText(EXCEEDS)).toBeInTheDocument();
      expect(labelled('Recorded deadline').getByRole('time')).toHaveAttribute('dateTime', LATER_DEADLINE);
      expect(screen.queryByDisplayValue('2026-10-29T02:00')).not.toBeInTheDocument();

      let arrive: (found: Shipment) => void = () => {};
      vi.mocked(getShipment).mockReturnValue(new Promise(resolve => { arrive = resolve; }));
      view.rerender(
        <MemoryRouter>
          <ShipmentDetail token="importer-token" shipmentId="8" basePath="/importer" viewerCompanyId={3} onSessionEnded={() => {}} />
        </MemoryRouter>,
      );
      expect(screen.queryByText(EXCEEDS)).not.toBeInTheDocument();
      expect(screen.queryByText('Deadline assessment')).not.toBeInTheDocument();
      arrive(saved);
      expect(await screen.findByText(NO_IMPACT)).toBeInTheDocument();

      vi.mocked(getShipment).mockRejectedValue(new ApiError('You do not have access to this shipment', 403));
      await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
      expect(screen.queryByText(NO_IMPACT)).not.toBeInTheDocument();
      expect(screen.queryByText('Deadline assessment')).not.toBeInTheDocument();
      expect(screen.queryByText('On schedule')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
