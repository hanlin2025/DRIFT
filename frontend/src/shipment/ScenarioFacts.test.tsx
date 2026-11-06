import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { decodeScenarioSnapshot } from './scenarioDomain';
import { ScenarioFacts } from './ScenarioFacts';
import { BINDINGS, EPOCH_ID, SESSION_ID, scenarioEnvelope } from './controlledScenarioTestFixtures';

function decoded(stage: 'BASELINE' | 'CHANGE' | 'WORSENED', key: 'A' | 'B' = 'A') {
  const envelope = scenarioEnvelope(stage);
  const binding = BINDINGS.find(row => row.scenarioShipmentKey === key)!;
  return decodeScenarioSnapshot(envelope, {
    sessionId: SESSION_ID,
    epochId: EPOCH_ID,
    snapshotId: envelope.snapshot!.snapshotId,
    revision: envelope.revision,
    shipmentId: binding.shipmentId,
    scenarioShipmentKey: key,
  });
}

describe('ScenarioFacts', () => {
  it.each([
    ['BASELINE', 'Low', '2026-10-01T06:00:00Z'],
    ['CHANGE', 'Medium', '2026-10-01T16:00:00Z'],
    ['WORSENED', 'High', '2026-10-02T08:00:00Z'],
  ] as const)('shows only the decoded %s scenario facts', (stage, risk, arrival) => {
    const snapshot = decoded(stage);
    expect(snapshot).toBeDefined();
    render(<ScenarioFacts snapshot={snapshot!} />);

    const facts = screen.getByRole('region', { name: 'Scenario snapshot facts' });
    expect(facts).toHaveTextContent(`SYNTHETIC SCENARIO · ${stage}`);
    expect(facts).toHaveTextContent('Backend-calculated facts. Risk, arrival estimates, connection windows and the deadline assessment below belong to this confirmed snapshot. Any AI interpretation is separate.');
    expect(within(facts).getByText(risk)).toBeInTheDocument();
    expect(facts.querySelector(`time[datetime="${arrival}"]`)).toBeInTheDocument();
    expect(facts).toHaveTextContent('Planned originNot recorded');
    expect(facts).toHaveTextContent('Planned destinationNot recorded');
    expect(facts).toHaveTextContent('Simulation reference cutoff (UTC)');
    expect(facts).toHaveTextContent('Mother observation time (UTC)');
    expect(facts).toHaveTextContent('Mother receipt time (UTC)');
  });

  it('renders B facts and its own deadline without using A values', () => {
    const snapshot = decoded('CHANGE', 'B');
    expect(snapshot).toBeDefined();
    render(<ScenarioFacts snapshot={snapshot!} />);

    const facts = screen.getByRole('region', { name: 'Scenario snapshot facts' });
    expect(facts).toHaveTextContent('DEMO-CDG279-Api-B');
    expect(facts.querySelector('time[datetime="2026-10-02T10:00:00Z"]')).toBeInTheDocument();
    expect(facts.querySelector('time[datetime="2026-10-01T12:00:00Z"]')).not.toBeInTheDocument();
  });
});
