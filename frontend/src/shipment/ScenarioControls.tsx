import { useId } from 'react';
import type { ScenarioSnapshotController } from './useScenarioSnapshot';

export type ScenarioControlsProps = Readonly<{ controller: ScenarioSnapshotController }>;

const SCENARIO_STAGES = ['BASELINE', 'CHANGE', 'WORSENED'] as const;
const EMAIL_STATUS_LABELS = {
  NOT_TRIGGERED: 'No synthetic email was triggered.',
  SUBMISSION_PENDING: 'Synthetic email submission is pending.',
  SUBMITTING: 'Synthetic email is being submitted to the approved demo recipient list.',
  SUBMITTED: 'Synthetic email submitted to the approved demo recipient list.',
  FAILED: 'Synthetic email submission failed. The WORSENED scenario remains complete.',
  PARTIAL_FAILURE: 'Synthetic email submission partially failed. The WORSENED scenario remains complete.',
  DISABLED: 'Synthetic email is disabled by configuration.',
  NO_APPROVED_RECIPIENTS: 'No valid approved synthetic email recipients are configured.',
} as const;

/** Presentation only. Capabilities and confirmed facts come from the coordinator. */
export function ScenarioControls({ controller }: ScenarioControlsProps) {
  const id = useId();
  const { session, snapshot, status, busy, error, pendingAction } = controller;
  const scenarioLabel = controller.binding?.scenarioShipmentKey
    ? `Scenario ${controller.binding.scenarioShipmentKey}` : 'this scenario';
  const enabled = !!session?.capabilities.canRead && !busy && !pendingAction && status === 'ready';
  const current = session?.state === 'UNSTARTED' ? 'UNSTARTED — no scenario snapshot.'
    : session && snapshot ? `${session.state} — ${snapshot.stage}` : 'No confirmed scenario state.';
  const confirmedStageOrder = snapshot?.stageOrder ?? null;
  return (
    <section aria-label="Controlled scenario" aria-describedby={`${id}-description`} aria-busy={busy}>
      <p id={`${id}-description`}>Controlled scenario. Actions advance {scenarioLabel} in its independent session; the other scenario keeps its own stage. Reset returns this session to UNSTARTED.</p>
      <div role="status" aria-live="polite" aria-atomic="true">
        <p>Last confirmed state: {current}</p>
        {snapshot && <p>Snapshot cutoff (UTC): <time dateTime={snapshot.simulationCutoffUtc}>{snapshot.simulationCutoffUtc}</time></p>}
        {snapshot?.stage === 'WORSENED' && session?.emailNotificationStatus
          && <p>Synthetic email status for {scenarioLabel}: {EMAIL_STATUS_LABELS[session.emailNotificationStatus]}</p>}
        {status === 'refreshing' && <p>Refreshing scenario…</p>}
        {status === 'loading' && <p>Loading scenario…</p>}
        {status === 'command' && <p>Confirming {pendingAction}…</p>}
        {status === 'reconciling' && <p>Reconciling scenario state…</p>}
        {pendingAction && !busy && <p>The command outcome is unconfirmed. Resolve it before another action.</p>}
        {session && !busy && !pendingAction && !error && !session.capabilities.canStart
          && !session.capabilities.canAdvance && !session.capabilities.canReset && <p>Scenario actions are unavailable for this session.</p>}
      </div>
      <ol className="scenario-stage-progress" aria-label="Scenario stage progression">
        {SCENARIO_STAGES.map((stage, index) => {
          const isCurrent = snapshot?.stage === stage;
          const isComplete = confirmedStageOrder !== null && confirmedStageOrder > index;
          const stateLabel = isCurrent ? 'Current' : isComplete ? 'Completed' : 'Upcoming';
          const classes = ['scenario-stage-step', isCurrent ? 'is-current' : '', isComplete ? 'is-complete' : '']
            .filter(Boolean).join(' ');
          return <li className={classes} key={stage} aria-current={isCurrent ? 'step' : undefined}>
            <span className="scenario-stage-order" aria-hidden="true">{index + 1}</span>
            <span className="scenario-stage-name"><strong>{stage}</strong><span>{stateLabel}</span></span>
          </li>;
        })}
      </ol>
      <p className="scenario-stage-guidance">Progress reflects the last server-confirmed stage. Start creates BASELINE; Next advances through CHANGE to WORSENED.</p>
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
