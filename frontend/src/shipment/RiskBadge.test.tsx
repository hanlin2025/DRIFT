import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PriorityScore, RiskBadge } from './RiskBadge';

afterEach(cleanup);

describe('risk badge', () => {
  it.each([
    ['LOW', 'Low'], ['MEDIUM', 'Medium'], ['HIGH', 'High'], ['CRITICAL', 'Critical'],
  ] as const)('labels %s as %s', (level, label) => {
    render(<RiskBadge risk={{ level, explanation: 'Because.' }} />);
    expect(screen.getByText(label)).toHaveClass('risk-badge', `risk-${level.toLowerCase()}`);
  });

  it('shows a shipment without a risk level as not assessed', () => {
    render(<RiskBadge risk={null} />);
    expect(screen.getByText('Not assessed')).toHaveClass('risk-badge', 'risk-unassessed');
  });
});

describe('priority score', () => {
  it('lists every reason the ranking returned and nothing else', () => {
    render(<PriorityScore reference="HBL-1" priority={{ score: 40, severity: 0.5, urgency: 0.4, confidence: 0.8, reasons: ['First reason.', 'Second reason.'] }} />);
    expect(screen.getByText('Priority 40')).toHaveAccessibleName('Priority 40 of 100 for HBL-1. Why this rank');
    expect(screen.getAllByRole('listitem', { hidden: true }).map(item => item.textContent)).toEqual(['First reason.', 'Second reason.']);
  });

  it('renders nothing without a priority', () => {
    const { container } = render(<PriorityScore reference="HBL-1" priority={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
