import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { MaritimeChangeEvent, MaritimeChangeEvents } from './api';
import { MaritimeChangeTimeline } from './MaritimeChangeTimeline';

const eta: MaritimeChangeEvent = {
  id: 'eta-change-2', sourceType: 'MPA_ETA_REVISION', sourceIdentity: 'MPA OCEANS-X / arrival feed',
  eventType: 'REVISED', sourceMode: 'RECORDED_REPLAY', sourceEventTime: '2026-10-29T02:00:00+08:00',
  observedAt: '2026-10-28T17:55:00Z', retrievedAt: '2026-10-28T18:00:00Z', detectedAt: '2026-10-28T18:00:01Z',
  previousValue: '2026-10-29 01:00 SGT', currentValue: '2026-10-29 02:00 SGT', provenance: 'Recorded manifest row 18',
  freshness: 'HISTORICAL', associationConfidence: 'UNVERIFIED_IMO_ONLY',
  compatibility: { activity: 'UNKNOWN', location: 'UNKNOWN', time: 'COMPATIBLE' }, requiresHumanReview: true,
};

function result(events: MaritimeChangeEvent[], availability: MaritimeChangeEvents['availability'] = 'AVAILABLE', nextCursor?: string | null): MaritimeChangeEvents {
  return { availability, events, ...(nextCursor !== undefined ? { nextCursor } : {}) };
}

describe('MaritimeChangeTimeline', () => {
  it('shows a detection-time timeline, source mode, provenance, confidence, risk and explicit AI navigation', () => {
    const older: MaritimeChangeEvent = {
      ...eta, id: 'weather-change-1', sourceType: 'NEA_WEATHER', sourceIdentity: 'NEA regional forecast',
      eventType: 'REVISED', sourceMode: 'SYNTHETIC_DEMONSTRATION', detectedAt: '2026-10-27T17:00:00Z',
      previousValue: null, currentValue: 'Moderate monsoon rain', provenance: 'Synthetic replay fixture', requiresHumanReview: false,
    };
    render(<MaritimeChangeTimeline result={result([older, eta], 'PARTIAL')} loading={false} loadingOlder={false} error={null} onRefresh={() => {}} onLoadOlder={() => {}}
      risk={{ level: 'HIGH', explanation: 'Less than 12 hours remain in the backend-calculated connection window.' }} />);

    expect(screen.getByText('PARTIAL')).toBeInTheDocument();
    const list = screen.getByRole('list');
    const entries = within(list).getAllByRole('listitem');
    expect(entries).toHaveLength(2);
    expect(entries[0]).toHaveTextContent('MPA arrival estimate revision');
    expect(entries[0]).toHaveTextContent('RECORDED_REPLAY');
    expect(entries[0]).toHaveTextContent('2026-10-29 01:00 SGT');
    expect(entries[0]).toHaveTextContent('2026-10-29 02:00 SGT');
    expect(entries[0]).toHaveTextContent('UNVERIFIED_IMO_ONLY');
    expect(entries[0]).toHaveTextContent('Human review needed.');
    expect(entries[1]).toHaveTextContent('NEA weather update');
    expect(entries[1]).toHaveTextContent('SYNTHETIC_DEMONSTRATION');
    expect(entries[1]).toHaveTextContent('Regional weather context does not establish a shipment-specific cause.');
    expect(screen.getByLabelText('Current backend risk assessment')).toHaveTextContent('High');
    expect(screen.getByRole('link', { name: 'Review AI assessment and request an explanation' })).toHaveAttribute('href', '#maritime-ai-assessment');
  });

  it('labels unavailable source data without representing it as an observation', () => {
    render(<MaritimeChangeTimeline result={result([], 'UNAVAILABLE')} loading={false} loadingOlder={false} error={null} onRefresh={() => {}} onLoadOlder={() => {}} risk={null} />);
    expect(screen.getByText('UNAVAILABLE')).toBeInTheDocument();
    expect(screen.getByText('Change information is unavailable. No source observation is implied.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('renders MPA notices as review context without asserting a vessel event', () => {
    render(<MaritimeChangeTimeline result={result([{
      ...eta, id: 'notice-2', sourceType: 'MPA_OFFICIAL_NOTICE', sourceIdentity: 'MPA Port Marine Notice 42',
      eventType: 'NEW', sourceMode: 'LIVE', previousValue: null,
      currentValue: 'Temporary works near the eastern channel', provenance: 'MPA notice page', requiresHumanReview: true,
    }])} loading={false} loadingOlder={false} error={null} onRefresh={() => {}} onLoadOlder={() => {}} risk={null} />);
    const event = within(screen.getByRole('list')).getByRole('listitem');
    expect(event).toHaveTextContent('MPA port notice');
    expect(event).toHaveTextContent('LIVE');
    expect(event).toHaveTextContent('A port notice does not by itself establish a vessel visit or berth event.');
  });

  it('displays nullable compatibility as unknown', () => {
    render(<MaritimeChangeTimeline result={result([{
      ...eta, id: 'legacy-compatibility', compatibility: { activity: null, location: null, time: 'UNKNOWN' },
    }])} loading={false} loadingOlder={false} error={null} onRefresh={() => {}} onLoadOlder={() => {}} risk={null} />);
    expect(within(screen.getByRole('list')).getByRole('listitem')).toHaveTextContent('Compatibility: activity UNKNOWN; location UNKNOWN; time UNKNOWN.');
  });

  it('marks historical backfills separately from a live observation', () => {
    render(<MaritimeChangeTimeline result={result([{
      ...eta, id: 'backfill-1', sourceMode: 'HISTORICAL_BACKFILL', eventType: 'REVISED',
      provenance: 'Historical retrieval', requiresHumanReview: false,
    }])} loading={false} loadingOlder={false} error={null} onRefresh={() => {}} onLoadOlder={() => {}} risk={null} />);
    const event = within(screen.getByRole('list')).getByRole('listitem');
    expect(event).toHaveTextContent('HISTORICAL_BACKFILL');
    expect(event).toHaveTextContent('Historical backfill; this record is not a newly observed live change.');
  });

  it('supports explicit refresh and loading feedback', async () => {
    const onRefresh = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<MaritimeChangeTimeline result={null} loading={false} loadingOlder={false} error={null} onRefresh={onRefresh} onLoadOlder={() => {}} risk={null} />);
    await user.click(screen.getByRole('button', { name: 'Refresh changes' }));
    expect(onRefresh).toHaveBeenCalledOnce();
    rerender(<MaritimeChangeTimeline result={null} loading loadingOlder={false} error={null} onRefresh={onRefresh} onLoadOlder={() => {}} risk={null} />);
    expect(screen.getByRole('button', { name: 'Refreshing…' })).toBeDisabled();
    expect(screen.getByText('Loading maritime changes…')).toBeInTheDocument();
  });

  it('requests older changes with the opaque cursor and reports pagination loading', async () => {
    const onLoadOlder = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<MaritimeChangeTimeline result={result([eta], 'AVAILABLE', 'opaque cursor + 2')} loading={false} loadingOlder={false}
      error={null} onRefresh={() => {}} onLoadOlder={onLoadOlder} risk={null} />);
    await user.click(screen.getByRole('button', { name: 'Load older changes' }));
    expect(onLoadOlder).toHaveBeenCalledWith('opaque cursor + 2');
    rerender(<MaritimeChangeTimeline result={result([eta], 'AVAILABLE', 'opaque cursor + 2')} loading={false} loadingOlder error={null}
      onRefresh={() => {}} onLoadOlder={onLoadOlder} risk={null} />);
    expect(screen.getByRole('button', { name: 'Loading older changes…' })).toBeDisabled();
  });
});
