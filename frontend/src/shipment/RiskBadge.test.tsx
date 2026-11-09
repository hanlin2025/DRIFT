import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { RiskBadge } from './RiskBadge';

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
