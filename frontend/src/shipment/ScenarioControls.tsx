import { useId } from 'react';
import type { ScenarioSnapshotController } from './useScenarioSnapshot';

export type ScenarioControlsProps = Readonly<{ controller: ScenarioSnapshotController }>;

/** Presentation only. Capabilities and confirmed facts come from the coordinator. */
export function ScenarioControls({ controller }: ScenarioControlsProps) {
  const id = useId();
  const { session, snapshot, status, busy, error, pendingAction } = controller;
  const enabled = !!session?.capabilities.canRead && !busy && !pendingAction && status === 'ready';
  const current = session?.state === 'UNSTARTED' ? 'UNSTARTED — no scenario snapshot.'
    : session && snapshot ? `${session.state} — ${snapshot.stage}` : 'No confirmed scenario state.';
  return (
    <section aria-label="Controlled scenario" aria-describedby={`${id}-description`} aria-busy={busy}>
      <p id={`${id}-description`}>Controlled scenario. Actions affect A and B in this session together. Reset returns this session to UNSTARTED.</p>
      <div role="status" aria-live="polite" aria-atomic="true">
        <p>Last confirmed state: {current}</p>
        {snapshot && <p>Snapshot cutoff (UTC): <time dateTime={snapshot.simulationCutoffUtc}>{snapshot.simulationCutoffUtc}</time></p>}
        {status === 'refreshing' && <p>Refreshing scenario…</p>}
        {status === 'loading' && <p>Loading scenario…</p>}
        {status === 'command' && <p>Confirming {pendingAction}…</p>}
        {status === 'reconciling' && <p>Reconciling scenario state…</p>}
        {pendingAction && !busy && <p>The command outcome is unconfirmed. Resolve it before another action.</p>}
        {session && !busy && !pendingAction && !error && !session.capabilities.canStart
          && !session.capabilities.canAdvance && !session.capabilities.canReset && <p>Scenario actions are unavailable for this session.</p>}
      </div>
      {error && <p role="alert">{error.message}</p>}
      <div role="group" aria-label="Scenario actions">
        <button type="button" disabled={!enabled || !session?.capabilities.canStart} onClick={() => void controller.start()}>Start</button>
        <button type="button" disabled={!enabled || !session?.capabilities.canAdvance} onClick={() => void controller.next()}>Next</button>
        <button type="button" disabled={!enabled || !session?.capabilities.canReset} onClick={() => void controller.reset()}>Reset</button>
        {pendingAction && <button type="button" disabled={!controller.canRetryCommand} onClick={() => void controller.retryCommand()}>Retry unconfirmed command</button>}
        {status === 'error' && !pendingAction && error?.status !== 401 && error?.status !== 403 && error?.code !== 'SCENARIO_DISABLED'
          && <button type="button" disabled={busy} onClick={() => void controller.refresh()}>Reload session</button>}
      </div>
    </section>
  );
}
