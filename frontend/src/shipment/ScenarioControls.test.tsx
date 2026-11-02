import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ScenarioControls } from './ScenarioControls';
import { ScenarioApiError, type ScenarioSessionEnvelope } from './scenarioApi';
import type { ScenarioSnapshotController } from './useScenarioSnapshot';

function session(stage?: 'BASELINE' | 'CHANGE' | 'WORSENED'): ScenarioSessionEnvelope {
  const stageOrder = stage === 'CHANGE' ? 1 : stage === 'WORSENED' ? 2 : 0;
  const base = {
    apiVersion: '1' as const, scenarioContractVersion: 'cdg279.synthetic-disruption.v1' as const,
    sessionId: 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf', scenarioId: 'CDG279-Api',
    epochId: '74dab1c6-1d4b-4889-8dde-0b31986fed28', revision: stage ? stageOrder + 1 : 0,
    capabilities: { canRead: true, canStart: !stage, canAdvance: !!stage && stage !== 'WORSENED', canReset: !!stage, canGenerateAssessment: false as const },
    shipments: [],
  };
  if (!stage) return { ...base, state: 'UNSTARTED', snapshot: null };
  return { ...base, state: stage === 'WORSENED' ? 'COMPLETE' : 'ACTIVE', snapshot: {
    snapshotId: '063052b2-fca1-4116-b559-f387ac6c2dc7', epochId: base.epochId, revision: base.revision,
    stage, stageOrder, stageCount: 3, fixtureStageId: 'fixture', simulationCutoffUtc: '2026-10-01T04:00:00Z',
    mapPlaybackCutoffUtc: '2026-10-01T04:00:00Z', shipments: [], aisObservations: [], maritimeContext: {},
  } };
}
function controller(envelope: ScenarioSessionEnvelope | null = session(), overrides: Partial<ScenarioSnapshotController> = {}): ScenarioSnapshotController {
  return { session: envelope, snapshot: envelope?.snapshot ?? null, binding: null,
    status: 'ready', busy: false, error: null, pendingAction: null, canRetryCommand: false,
    start: vi.fn().mockResolvedValue(undefined), next: vi.fn().mockResolvedValue(undefined),
    reset: vi.fn().mockResolvedValue(undefined), refresh: vi.fn().mockResolvedValue(undefined),
    retryCommand: vi.fn().mockResolvedValue(undefined), ...overrides };
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('ScenarioControls', () => {
  it('provides a named region, action group and keyboard-accessible Start', async () => {
    const model = controller();
    render(<ScenarioControls controller={model} />);
    const region = screen.getByRole('region', { name: 'Controlled scenario' });
    expect(region).toHaveAttribute('aria-busy', 'false');
    expect(region).toHaveAccessibleDescription(/Actions affect A and B/);
    expect(within(region).getByRole('group', { name: 'Scenario actions' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('UNSTARTED — no scenario snapshot.');
    expect(screen.getByRole('button', { name: 'Start' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Reset' })).toBeDisabled();
    const user = userEvent.setup();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Start' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(model.start).toHaveBeenCalledTimes(1);
    expect(model.next).not.toHaveBeenCalled();
  });
  it.each(['BASELINE', 'CHANGE', 'WORSENED'] as const)('shows only the confirmed %s state and source cutoff', async stage => {
    const model = controller(session(stage));
    render(<ScenarioControls controller={model} />);
    expect(screen.getByRole('status')).toHaveTextContent(`${model.session?.state} — ${stage}`);
    expect(screen.getByText('2026-10-01T04:00:00Z')).toHaveAttribute('datetime', '2026-10-01T04:00:00Z');
    expect(screen.getByRole('button', { name: 'Start' })).toBeDisabled();
    const next = screen.getByRole('button', { name: 'Next' });
    if (stage === 'WORSENED') expect(next).toBeDisabled();
    else {
      expect(next).toBeEnabled();
      await userEvent.setup().click(next);
      expect(model.next).toHaveBeenCalledTimes(1);
    }
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reset' }));
    expect(model.reset).toHaveBeenCalledTimes(1);
  });
  it('uses backend capabilities rather than deriving permissions from the stage', () => {
    const final = session('WORSENED');
    render(<ScenarioControls controller={controller({ ...final, capabilities: { ...final.capabilities, canAdvance: true, canReset: false } })} />);
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Reset' })).toBeDisabled();
  });
  it('explains read-only capabilities without granting controls', () => {
    const readOnly = session('BASELINE');
    render(<ScenarioControls controller={controller({ ...readOnly, capabilities: {
      ...readOnly.capabilities, canStart: false, canAdvance: false, canReset: false,
    } })} />);
    expect(screen.getByRole('status')).toHaveTextContent('Scenario actions are unavailable for this session.');
    for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled();
  });
  it.each(['loading', 'refreshing', 'command', 'reconciling'] as const)('announces %s and prevents stage commands', status => {
    render(<ScenarioControls controller={controller(session('BASELINE'), { status, busy: true, pendingAction: status === 'command' ? 'next' : null })} />);
    expect(screen.getByRole('region')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('ACTIVE — BASELINE');
    expect(screen.getByRole('status')).not.toHaveTextContent('CHANGE');
    for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled();
  });
  it('shows no invented stage or cutoff before a session exists', () => {
    render(<ScenarioControls controller={controller(null, { status: 'loading', busy: true })} />);
    expect(screen.getByRole('status')).toHaveTextContent('No confirmed scenario state.');
    expect(screen.queryByText(/Snapshot cutoff/)).not.toBeInTheDocument();
  });
  it('resolves an unconfirmed command through the dedicated retry action', async () => {
    const model = controller(session('BASELINE'), { status: 'unconfirmed', pendingAction: 'next', canRetryCommand: true,
      error: new ScenarioApiError('The update could not be confirmed.', 0, 'network', null, false, true) });
    render(<ScenarioControls controller={model} />);
    expect(screen.getByRole('alert')).toHaveTextContent('The update could not be confirmed.');
    expect(screen.getByRole('status')).toHaveTextContent('The command outcome is unconfirmed.');
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry unconfirmed command' }));
    expect(model.retryCommand).toHaveBeenCalledTimes(1);
    expect(model.next).not.toHaveBeenCalled();
  });
  it.each([[401, 'UNAUTHENTICATED'], [403, 'SCENARIO_ACCESS_DENIED'], [404, 'SCENARIO_DISABLED']] as const)(
    'shows the real %i authorization/disabled error', (status, code) => {
      render(<ScenarioControls controller={controller(null, { status: 'error', error: new ScenarioApiError('Access unavailable.', status, 'api', code) })} />);
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable.');
      expect(screen.queryByRole('button', { name: 'Reload session' })).not.toBeInTheDocument();
      for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled();
    });
  it('offers explicit reload for unavailable service without claiming success', async () => {
    const model = controller(null, { status: 'error', error: new ScenarioApiError('Demo configuration is missing.', 503, 'api', 'SCENARIO_NOT_CONFIGURED') });
    render(<ScenarioControls controller={model} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Demo configuration is missing.');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reload session' }));
    expect(model.refresh).toHaveBeenCalledTimes(1);
    expect(model.start).not.toHaveBeenCalled();
  });
  it('clears the old stage and cutoff when the coordinator confirms Reset', () => {
    const { rerender } = render(<ScenarioControls controller={controller(session('CHANGE'))} />);
    const reset = { ...session(), epochId: '4618e3c7-2006-4901-a211-01fbbaf8186c' };
    rerender(<ScenarioControls controller={controller(reset)} />);
    expect(screen.getByRole('status')).toHaveTextContent('UNSTARTED — no scenario snapshot.');
    expect(screen.queryByText(/CHANGE/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Snapshot cutoff/)).not.toBeInTheDocument();
  });
  it('makes no network or provider request while rendering or rerendering', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const { rerender } = render(<ScenarioControls controller={controller(session('BASELINE'))} />);
    rerender(<ScenarioControls controller={controller(session('CHANGE'))} />);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByText(/risk|ETA|AI assessment|successfully/i)).not.toBeInTheDocument();
  });
});
