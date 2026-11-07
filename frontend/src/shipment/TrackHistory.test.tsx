import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { VesselTrack } from './api';
import { TrackHistory } from './TrackHistory';

const point = {
  id: 21, latitude: 1.25, longitude: 103.75, speedOverGroundKnots: 4.2,
  courseOverGroundDegrees: 90, trueHeadingDegrees: 91, ingestedAt: '2026-10-13T08:15:00Z',
  source: 'AIS_STREAM' as const, positionValid: true,
};

const history: VesselTrack = {
  vessel: 'MOTHER', vesselName: 'Configured mother role', mmsi: 'stored-by-backend',
  windowHours: 24, from: '2026-10-12T08:15:00Z', to: '2026-10-13T08:15:00Z',
  slowdownThresholdKnots: 3, pointLimit: 500, truncated: false, points: [point],
  cutoff: '2026-10-13T08:15:00Z', timestampSemantics: 'DRIFT_RECEIPT_TIME',
  context: 'RECORDED', status: 'STALE', feedStatus: 'OFFLINE', fallbackUsed: false,
  captureId: 'configured-capture',
};

function renderHistory(props: Partial<Parameters<typeof TrackHistory>[0]> = {}) {
  return render(<TrackHistory vessel="MOTHER" hours={24} history={history} loading={false} failed={false}
    feederAssigned selectedId={point.id} onVessel={vi.fn()} onHours={vi.fn()} onSelectPoint={vi.fn()}
    {...props} />);
}

describe('track history presentation', () => {
  it('labels AIS_STREAM observations as recorded when the response says RECORDED', () => {
    renderHistory({ mode: 'live' });

    expect(screen.getByText('Recorded AIS · receipt-time observations')).toBeInTheDocument();
    expect(screen.getByText('Recorded · AIS_STREAM')).toBeInTheDocument();
    expect(screen.getByText('This track is stale at its requested receipt-time cutoff.')).toBeInTheDocument();
    expect(screen.getByText('Recorded AIS is a display reference. It does not change shipment ETA, risk or operational decisions.')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Track source details'));
    expect(screen.getByText('configured-capture')).toBeInTheDocument();
    expect(screen.getByText('DRIFT_RECEIPT_TIME')).toBeInTheDocument();
  });

  it('submits a UTC receipt-time cutoff and keeps the scenario reference separate', () => {
    const onCutoffRequest = vi.fn();
    renderHistory({ mode: 'recorded', cutoff: '2026-10-13T08:15:00Z', scenarioReferenceCutoff: '2026-10-13T07:00:00Z', onCutoffRequest });
    const input = screen.getByLabelText('Recorded AIS receipt-time cutoff (UTC)');
    fireEvent.change(input, { target: { value: '2026-10-13T16:30:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Load cutoff' }));

    expect(onCutoffRequest).toHaveBeenCalledWith('2026-10-13T16:30:00.000Z');
    expect(screen.getByText(/Scenario reference:/)).toHaveTextContent('Scenario reference:');
    expect(screen.getByText(/Recorded receipt-time cutoff:/)).toHaveTextContent('Recorded receipt-time cutoff:');
    expect(input).toHaveValue('2026-10-13T16:30');
  });

  it('keeps a last-known fallback readable when the requested window has no points', () => {
    const noPoints = { ...history, points: [], fallbackUsed: true, lastKnownPosition: point };
    renderHistory({ history: noPoints, selectedId: null });

    expect(screen.getByText(/No positions were received in this window/)).toHaveTextContent('Last known position: 1.2500, 103.7500');
    expect(screen.getByRole('region', { name: 'Track history' })).toHaveTextContent('STALE');
    fireEvent.click(screen.getByText('Track source details'));
    const details = screen.getByText('Track source details').closest('details');
    expect(within(details as HTMLElement).getByText('Yes')).toBeInTheDocument();
  });
});
