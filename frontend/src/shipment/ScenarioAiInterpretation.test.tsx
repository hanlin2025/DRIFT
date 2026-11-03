import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Session } from '../session/session';
import type { Shipment } from './api';
import { decodeScenarioSnapshot } from './scenarioDomain';
import { EPOCH_ID, SESSION_ID, scenarioEnvelope } from './controlledScenarioTestFixtures';
import { ScenarioAiInterpretation } from './ScenarioAiInterpretation';

const viewer: Session = { token: 'test-token', expiresAt: '2099-01-01T00:00:00Z', id: 8,
  fullName: 'Demo Viewer', email: 'viewer@example.test', role: 'IMPORTER', company: { id: 2, code: 'DEMO', name: 'Demo' } };
const anchor: Shipment = {
  id: 142, shipmentReference: 'DEMO-A-PORTFOLIO', origin: 'Live Origin', destination: 'Live Destination',
  transshipmentPort: 'Live Port', motherVessel: 'Live Mother', plannedMotherArrivalAt: '2099-05-06T07:08:09Z',
  feederVessel: 'Live Feeder', plannedFeederDepartureAt: '2099-05-07T07:08:09Z', createdAt: '2025-01-01T00:00:00Z',
  version: 4, companyId: 2, connectionWindow: { duration: 'Live window', totalSeconds: 1 },
  remainingConnectionWindow: null, risk: { level: 'CRITICAL', explanation: 'LIVE RISK sentinel' }, status: 'ACTIVE',
  motherVesselPosition: null, feederVesselPosition: null, arrivalComparison: null,
};
function snapshot(stage: 'BASELINE' | 'CHANGE' | 'WORSENED' = 'CHANGE') {
  const envelope = scenarioEnvelope(stage);
  return decodeScenarioSnapshot(envelope, { sessionId: SESSION_ID, epochId: EPOCH_ID,
    snapshotId: envelope.snapshot!.snapshotId, revision: envelope.revision, shipmentId: 142, scenarioShipmentKey: 'A' })!;
}
const assessment = {
  assessmentId: null, state: 'GENERATED', validationStatus: 'VALID', bundleVersion: 'cdg246.maritime-evidence-bundle.v1',
  bundleHash: 'a'.repeat(64), assessmentCutoffUtc: '2026-10-01T04:00:00Z', provider: 'openai-responses', model: 'test-model',
  promptVersion: 'cdg290.scenario-grounded-v1.1', generatedAtUtc: '2026-11-03T00:00:00Z', limitation: null,
  interpretation: { summary: 'Review the grounded interpretation and limitations.', claims: [], uncertainties: [],
    followUpConsiderations: ['Ask the operations team to verify current evidence.'], evidenceSufficient: true, formalRecommendationCode: null },
  deterministicSummary: { summaryVersion: 'v1', label: 'Backend deterministic facts', shipmentScheduleFacts: [], evidenceFacts: [], limitations: [] },
};
function http(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, headers: { get: () => 'application/json' }, json: async () => body } as unknown as Response;
}
function responseFor(selected = snapshot()) {
  return { sessionId: selected.sessionId, epochId: selected.epochId, snapshotId: selected.snapshotId,
    revision: selected.revision, simulationCutoffUtc: selected.simulationCutoffUtc,
    scenarioShipmentKey: selected.selectedShipment.scenarioShipmentKey, shipmentId: anchor.id, assessment };
}
function props(selected = snapshot(), onSessionEnded = vi.fn()) {
  return { enabled: true, viewer, anchor, authContextGeneration: 'auth-generation-1', snapshot: selected,
    onSessionEnded, onSnapshotStale: vi.fn(), onAuthorizationLost: vi.fn() };
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('ScenarioAiInterpretation', () => {
  it('waits for an explicit click and displays only the validated bound response', async () => {
    const user = userEvent.setup();
    const fetch = vi.fn().mockResolvedValue(http(responseFor()));
    vi.stubGlobal('fetch', fetch);
    render(<ScenarioAiInterpretation {...props()} />);

    expect(fetch).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByText(/Provider-generated assessment from openai-responses/)).toBeInTheDocument();
    expect(screen.getByText('SYNTHETIC SCENARIO INTERPRETATION')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledOnce();
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toMatchObject({
      scenarioShipmentKey: 'A', expectedEpochId: EPOCH_ID, expectedRevision: 2,
      expectedSnapshotId: snapshot().snapshotId, expectedSimulationCutoffUtc: snapshot().simulationCutoffUtc,
    });
  });

  it('masks an earlier stage immediately and ignores its late response after stage change', async () => {
    const user = userEvent.setup();
    let release!: (value: Response) => void;
    const fetch = vi.fn().mockImplementation(() => new Promise<Response>(resolve => { release = resolve; }));
    vi.stubGlobal('fetch', fetch);
    const view = render(<ScenarioAiInterpretation {...props(snapshot('CHANGE'))} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());

    view.rerender(<ScenarioAiInterpretation {...props(snapshot('WORSENED'))} />);
    expect(screen.queryByText(/Provider-generated assessment/)).not.toBeInTheDocument();
    await act(async () => { release(http(responseFor(snapshot('CHANGE')))); });
    expect(screen.queryByText(/Provider-generated assessment/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Generate AI Interpretation' })).toBeEnabled();
  });

  it('clears protected output and ends the login flow on 401', async () => {
    const user = userEvent.setup();
    const onSessionEnded = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(http({ code: 'UNAUTHENTICATED', message: 'Ended', retryable: false }, 401)));
    render(<ScenarioAiInterpretation {...props(snapshot(), onSessionEnded)} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(onSessionEnded).toHaveBeenCalledOnce();
  });

  it('clears the interpretation and refreshes coordinator state after stale snapshot conflicts', async () => {
    const user = userEvent.setup();
    const onSnapshotStale = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(http({ code: 'STALE_REVISION', message: 'Stage changed.', retryable: false }, 409)));
    render(<ScenarioAiInterpretation {...props()} onSnapshotStale={onSnapshotStale} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Stage changed.');
    expect(onSnapshotStale).toHaveBeenCalledOnce();
    expect(screen.queryByText(/Provider-generated assessment/)).not.toBeInTheDocument();
  });

  it('clears scenario interpretation when the AI endpoint denies access', async () => {
    const user = userEvent.setup();
    const onAuthorizationLost = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(http({ code: 'SCENARIO_ACCESS_DENIED', message: 'Access denied.', retryable: false }, 403)));
    render(<ScenarioAiInterpretation {...props()} onAuthorizationLost={onAuthorizationLost} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Access denied.');
    expect(onAuthorizationLost).toHaveBeenCalledOnce();
  });

  it('renders no generation controls when the explicit frontend gate is off', () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const result = render(<ScenarioAiInterpretation {...props()} enabled={false} />);
    expect(result.container).toBeEmptyDOMElement();
    expect(fetch).not.toHaveBeenCalled();
  });
});
