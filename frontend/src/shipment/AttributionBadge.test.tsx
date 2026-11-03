import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AttributionBadge, AttributionFact } from './AttributionBadge';

afterEach(cleanup);

describe('attribution badge', () => {
  it.each([
    ['PORT_CONDITION', 'Port'], ['WEATHER', 'Weather'], ['MULTIPLE', 'Port + weather'], ['VESSEL_BEHIND_SCHEDULE', 'Vessel behind'],
  ] as const)('labels %s as %s', (cause, label) => {
    render(<AttributionBadge cause={cause} />);
    expect(screen.getByText(label)).toHaveClass('cause-badge');
  });

  it.each(['NONE_DETECTED', 'UNAVAILABLE', null] as const)('shows nothing for %s', cause => {
    const { container } = render(<AttributionBadge cause={cause} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('likely cause fact', () => {
  it('names the cause, the signals that were read, and a raised risk', () => {
    render(<dl><AttributionFact attribution={{
      cause: 'MULTIPLE',
      summary: 'MPA port data and NEA weather near the port both support a delay.',
      corroboratingSources: 2,
      riskRaisedFrom: 'MEDIUM',
      signals: [
        { source: 'MPA_OFFICIAL_NOTICE', association: 'UNCONFIRMED_CONTEXT', supportsDelay: true, summary: 'MPA notice for Tuas, Singapore: Port congestion', observedAt: '2026-11-01', provenance: 'MPA' },
        { source: 'NEA_WEATHER', association: 'REGIONAL_CONTEXT', supportsDelay: true, summary: 'NEA reports Thundery Showers at Tuas, near the port.', observedAt: null, provenance: 'NEA' },
      ],
    }} /></dl>);

    expect(screen.getByText('Likely cause')).toBeInTheDocument();
    expect(screen.getByText('Port + weather')).toBeInTheDocument();
    expect(screen.getByText('Risk raised from Medium by external port data.')).toBeInTheDocument();
    expect(screen.getByText('MPA notice')).toBeInTheDocument();
    expect(screen.getByText('NEA weather')).toBeInTheDocument();
  });

  it('shows nothing without an attribution', () => {
    const { container } = render(<dl><AttributionFact attribution={null} /></dl>);
    expect(container.querySelector('dt')).toBeNull();
  });
});
