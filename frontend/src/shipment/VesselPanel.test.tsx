import { act, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { VesselPosition } from './api';
import { STALE_AFTER_MS } from './positionNotice';
import { AGE_TICK_MS, compassPoint, formatAge, formatCoordinates, NO_POSITION, STALE_DETAIL, VesselPanel } from './VesselPanel';

const now = Date.parse('2026-10-07T12:00:00Z');

function fix(ingestedAt: string, changes: Partial<VesselPosition> = {}): VesselPosition {
  return {
    mmsi: '563001234',
    vesselName: 'PACIFIC HORIZON',
    latitude: 1.264,
    longitude: 103.82,
    speedOverGroundKnots: 12.4,
    courseOverGroundDegrees: 175,
    trueHeadingDegrees: 176,
    ingestedAt,
    ...changes,
  };
}

function minutesAgo(minutes: number) {
  return new Date(now - minutes * 60_000).toISOString();
}

function reading(label: string) {
  const panel = screen.getByRole('complementary', { name: 'Mother vessel AIS' });
  return within(panel).getByText(label).nextElementSibling;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.setSystemTime(now);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('mother vessel AIS panel', () => {
  it('shows the latest fix in a readable form', () => {
    render(<VesselPanel name="MV Pacific Horizon" position={fix(minutesAgo(12))} />);
    expect(screen.getByText('PACIFIC HORIZON')).toBeInTheDocument();
    expect(screen.getByText('MMSI 563001234')).toBeInTheDocument();
    expect(reading('Position')).toHaveTextContent('1.2640° N, 103.8200° E');
    expect(reading('Speed')).toHaveTextContent('12.4 kn');
    expect(reading('Course')).toHaveTextContent('175° (S)');
    expect(reading('Heading')).toHaveTextContent('176° (S)');
    expect(reading('Last report')).toHaveTextContent('Updated 12 min ago');
    expect(screen.queryByText('Stale')).not.toBeInTheDocument();
  });

  it('says when a reading was not reported', () => {
    render(<VesselPanel name="MV Pacific Horizon" position={fix(minutesAgo(5), {
      vesselName: null,
      speedOverGroundKnots: null,
      courseOverGroundDegrees: null,
      trueHeadingDegrees: null,
    })} />);
    expect(screen.getByText('MV Pacific Horizon')).toBeInTheDocument();
    for (const label of ['Speed', 'Course', 'Heading']) {
      expect(reading(label)).toHaveTextContent('Not reported');
      expect(reading(label)).toHaveClass('is-missing');
    }
  });

  it('says when no position has been received', () => {
    render(<VesselPanel name="MV Pacific Horizon" position={null} />);
    expect(screen.getByText(NO_POSITION)).toBeInTheDocument();
    expect(screen.queryByText('Speed')).not.toBeInTheDocument();
  });

  it('marks a position older than six hours as stale', () => {
    const { rerender } = render(<VesselPanel name="MV Pacific Horizon" position={fix(new Date(now - STALE_AFTER_MS).toISOString())} />);
    expect(screen.queryByText('Stale')).not.toBeInTheDocument();
    rerender(<VesselPanel name="MV Pacific Horizon" position={fix(new Date(now - STALE_AFTER_MS - 1000).toISOString())} />);
    expect(screen.getByText('Stale')).toBeInTheDocument();
    expect(screen.getByText(STALE_DETAIL, { exact: false })).toBeInTheDocument();
  });

  it('advances the age and stale state while the page stays open', async () => {
    render(<VesselPanel name="MV Pacific Horizon" position={fix(new Date(now - STALE_AFTER_MS + 60_000).toISOString())} />);
    expect(reading('Last report')).toHaveTextContent('Updated 5 h 59 min ago');
    expect(screen.queryByText('Stale')).not.toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(AGE_TICK_MS * 4); });
    expect(reading('Last report')).toHaveTextContent('Updated 6 h 1 min ago');
    expect(screen.getByText('Stale')).toBeInTheDocument();
  });
});

describe('panel formatting', () => {
  it('describes the age of a report', () => {
    expect(formatAge(minutesAgo(0.5), now)).toBe('just now');
    expect(formatAge(minutesAgo(1), now)).toBe('1 min ago');
    expect(formatAge(minutesAgo(59), now)).toBe('59 min ago');
    expect(formatAge(minutesAgo(120), now)).toBe('2 h ago');
    expect(formatAge(minutesAgo(135), now)).toBe('2 h 15 min ago');
    expect(formatAge(minutesAgo(60 * 50), now)).toBe('2 d ago');
    expect(formatAge(minutesAgo(-5), now)).toBe('just now');
  });

  it('labels hemispheres and compass points', () => {
    expect(formatCoordinates(-33.86, -151.2)).toBe('33.8600° S, 151.2000° W');
    expect(compassPoint(0)).toBe('N');
    expect(compassPoint(44)).toBe('NE');
    expect(compassPoint(270)).toBe('W');
    expect(compassPoint(359)).toBe('N');
  });
});
