import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Session } from '../session/session';
import type { Shipment } from './api';
import { decodeScenarioSnapshot, type DecodedScenarioSnapshot } from './scenarioDomain';
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
const ASSESSMENT_IDS = {
  'BASELINE-A': '3574f541-396e-490a-addd-b426ce77f0eb',
  'BASELINE-B': 'bc72814a-4a53-461e-91af-2f094f79b89b',
  'CHANGE-A': 'a9172fc5-5cea-4344-8058-a27c40aa4eb1',
  'CHANGE-B': '39522c20-b6fb-4250-b3a7-8f546c217d7e',
  'WORSENED-A': '4592f5f1-6f45-40b6-8131-4d57ce43e6b1',
  'WORSENED-B': '32bb2f28-7608-4275-86cd-b570967ea34d',
  'MODEL_UNAVAILABLE': 'ef2bcacb-cc8e-4b2f-a512-1c12b7065eb8',
} as const;
type Stage = 'BASELINE' | 'CHANGE' | 'WORSENED';

function snapshot(stage: Stage = 'CHANGE', key: 'A' | 'B' = 'A') {
  const shipmentId = key === 'A' ? 142 : 143;
  const envelope = scenarioEnvelope(stage);
  return decodeScenarioSnapshot(envelope, { sessionId: SESSION_ID, epochId: EPOCH_ID,
    snapshotId: envelope.snapshot!.snapshotId, revision: envelope.revision, shipmentId, scenarioShipmentKey: key })!;
}

function assessmentFor(stage: Stage, key: 'A' | 'B', assessmentId: string, options: {
  validationStatus?: string; interpretation?: unknown; limitation?: string | null; summary?: string;
} = {}) {
  const abstained = stage === 'BASELINE' || options.validationStatus === 'MODEL_UNAVAILABLE';
  return {
    assessmentId, state: abstained ? 'ABSTAINED' : 'GENERATED',
    validationStatus: options.validationStatus ?? (stage === 'BASELINE' ? 'INSUFFICIENT_EVIDENCE' : 'VALID'),
    bundleVersion: 'cdg246.maritime-evidence-bundle.v1', bundleHash: 'a'.repeat(64),
    assessmentCutoffUtc: snapshot(stage, key).simulationCutoffUtc,
    provider: 'offline-scenario-quality-stub', model: abstained ? '' : 'deterministic-fixture',
    promptVersion: 'cdg290.scenario-grounded-v1.5', generatedAtUtc: '2026-11-06T15:00:00Z',
    limitation: options.limitation ?? (abstained ? (options.validationStatus === 'MODEL_UNAVAILABLE'
      ? 'The configured provider returned no interpretation.'
      : 'No sufficient, current synthetic evidence is available for inference.') : null),
    interpretation: options.interpretation === undefined ? (abstained ? null : {
      summary: options.summary ?? `Synthetic ${stage.toLowerCase()} evidence warrants a human review for shipment ${key}.`,
      claims: [{
        statement: `The synthetic expected-arrival revision for shipment ${key} warrants review alongside its backend-calculated deadline comparison.`,
        evidenceIds: ['cdg279-v1-revision-1'], applicability: 'Selected fictional scenario shipment only.',
        relationshipType: 'DELAY_COMPARISON_REVIEW', factReferences: ['business:DELAY_COMPARISON'], riskReview: null,
      }], uncertainties: ['The synthetic source does not establish a real-world delay.'],
      followUpConsiderations: ['Verify the cited revision and deadline with the operations team.'],
      evidenceSufficient: true, formalRecommendationCode: null,
    }) : options.interpretation,
    deterministicSummary: {
      summaryVersion: 'cdg290.scenario-deterministic-summary.v1', label: 'Backend-calculated scenario facts',
      shipmentScheduleFacts: [],
      evidenceFacts: stage === 'BASELINE' ? [] : [{
        factReference: 'evidence:cdg279-v1-revision-1', factType: 'MPA_ETA_REVISION', subject: null,
        eventType: 'EXPECTED_ARRIVAL', timestamp: '2026-10-01T03:00:00Z', previousValue: null,
        currentValue: '2026-10-01T06:00:00Z', evidenceId: 'cdg279-v1-revision-1', sourceType: 'MPA_ETA_REVISION',
        sourceMode: 'SYNTHETIC_DEMONSTRATION', associationScope: 'VESSEL_OBSERVATION',
        citation: 'Synthetic fixture capture; fictional demonstration evidence.', observedAt: null,
        retrievedAtUtc: '2026-10-01T03:30:00Z',
      }],
      limitations: ['Synthetic demonstration evidence only.'],
    },
  };
}

function responseFor(selected = snapshot(), assessmentOverride?: ReturnType<typeof assessmentFor>, metadata: Record<string, unknown> = {}) {
  const key = selected.selectedShipment.scenarioShipmentKey;
  const stage = selected.stage;
  const id = ASSESSMENT_IDS[`${stage}-${key}` as keyof typeof ASSESSMENT_IDS];
  return {
    sessionId: selected.sessionId, epochId: selected.epochId, snapshotId: selected.snapshotId,
    revision: selected.revision, simulationCutoffUtc: selected.simulationCutoffUtc,
    scenarioShipmentKey: key, shipmentId: selected.selectedShipment.sourceShipmentId,
    assessment: assessmentOverride ?? assessmentFor(stage, key, id),
    scenarioId: selected.scenarioId, scenarioVersion: selected.scenarioContractVersion,
    scenarioShipmentReference: selected.selectedShipment.scenarioShipmentReference, stage,
    evidenceHash: 'b'.repeat(64), contextHash: 'c'.repeat(64), ...metadata,
  };
}

function http(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, headers: { get: () => 'application/json' }, json: async () => body } as unknown as Response;
}
function props(selected = snapshot(), overrides: {
  viewer?: Session; anchor?: Shipment; authContextGeneration?: string;
  onSessionEnded?: () => void; onSnapshotStale?: () => void; onAuthorizationLost?: () => void;
} = {}) {
  return { enabled: true, viewer: overrides.viewer ?? viewer, anchor: overrides.anchor ?? (selected.selectedShipment.scenarioShipmentKey === 'A' ? anchor : { ...anchor, id: 143 }),
    authContextGeneration: overrides.authContextGeneration ?? 'auth-generation-1', snapshot: selected,
    onSessionEnded: overrides.onSessionEnded ?? vi.fn(), onSnapshotStale: overrides.onSnapshotStale ?? vi.fn(),
    onAuthorizationLost: overrides.onAuthorizationLost ?? vi.fn() };
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('ScenarioAiInterpretation', () => {
  it.each([
    ['BASELINE', 'A', 'ABSTAINED', 'INSUFFICIENT_EVIDENCE'],
    ['BASELINE', 'B', 'ABSTAINED', 'INSUFFICIENT_EVIDENCE'],
    ['CHANGE', 'A', 'GENERATED', 'VALID'],
    ['CHANGE', 'B', 'GENERATED', 'VALID'],
    ['WORSENED', 'A', 'GENERATED', 'VALID'],
    ['WORSENED', 'B', 'GENERATED', 'VALID'],
  ] as const)('renders the persisted %s %s %s assessment from the authenticated GET', async (stage, key, state, validationStatus) => {
    const selected = snapshot(stage, key);
    const id = ASSESSMENT_IDS[`${stage}-${key}` as keyof typeof ASSESSMENT_IDS];
    const postBody = responseFor(selected, assessmentFor(stage, key, id, { summary: 'POST generation envelope.' }));
    const getBody = responseFor(selected, assessmentFor(stage, key, id, { summary: 'Persisted GET interpretation.' }));
    const fetch = vi.fn().mockResolvedValueOnce(http(postBody)).mockResolvedValueOnce(http(getBody));
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    render(<ScenarioAiInterpretation {...props(selected)} />);

    expect(fetch).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    if (state === 'GENERATED') {
      const panel = await screen.findByRole('region', { name: 'Scenario AI interpretation' });
      expect(panel).toHaveTextContent('Controlled synthetic stub assessment. It uses fictional evidence and is not an external provider response.');
      await user.click(within(panel).getByText('Assessment and evidence details'));
      expect(panel).toHaveTextContent('Persisted GET interpretation.');
      expect(panel).not.toHaveTextContent('POST generation envelope.');
    } else {
      const panel = await screen.findByRole('region', { name: 'Scenario AI interpretation' });
      expect(within(panel).getByRole('region', { name: 'No validated interpretation' }))
        .toHaveTextContent('The assessment abstained; no AI interpretation is shown.');
      expect(panel).toHaveTextContent('No sufficient, current synthetic evidence is available for inference.');
      expect(panel).not.toHaveTextContent('Provider-generated assessment');
    }
    expect(fetch).toHaveBeenCalledTimes(2);
    const [postPath, postInit] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(postPath).toContain('/interpretation');
    expect(postInit.method).toBe('POST');
    expect(new Headers(postInit.headers).get('Authorization')).toBe('Bearer test-token');
    expect(JSON.parse(String(postInit.body))).toMatchObject({
      scenarioShipmentKey: key, expectedEpochId: selected.epochId, expectedRevision: selected.revision,
      expectedSnapshotId: selected.snapshotId, expectedSimulationCutoffUtc: selected.simulationCutoffUtc,
    });
    const [getPath, getInit] = fetch.mock.calls[1] as unknown as [string, RequestInit];
    expect(getPath).toContain(`/assessments/${id}`);
    expect(getInit.method).toBe('GET');
    expect(new Headers(getInit.headers).get('Authorization')).toBe('Bearer test-token');
  });

  it('shows MODEL_UNAVAILABLE as an abstention with its persisted explanation', async () => {
    const selected = snapshot('CHANGE', 'A');
    const id = ASSESSMENT_IDS.MODEL_UNAVAILABLE;
    const modelUnavailable = assessmentFor('CHANGE', 'A', id, { validationStatus: 'MODEL_UNAVAILABLE' });
    const body = responseFor(selected, modelUnavailable);
    const fetch = vi.fn().mockResolvedValueOnce(http(body)).mockResolvedValueOnce(http(body));
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    render(<ScenarioAiInterpretation {...props(selected)} />);

    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    const panel = await screen.findByRole('region', { name: 'Scenario AI interpretation' });
    expect(within(panel).getByText(/abstained.*no AI interpretation/i)).toBeInTheDocument();
    expect(panel).toHaveTextContent(/Model unavailable/i);
    expect(panel).toHaveTextContent('The configured provider returned no interpretation.');
    expect(panel).not.toHaveTextContent('Provider-generated assessment');
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('shows a loading state through POST and authenticated persisted GET', async () => {
    const selected = snapshot();
    const body = responseFor(selected);
    let releasePost!: (value: Response) => void;
    let releaseGet!: (value: Response) => void;
    const fetch = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>(resolve => { releasePost = resolve; }))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { releaseGet = resolve; }));
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    render(<ScenarioAiInterpretation {...props(selected)} />);

    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Generating interpretation…' })).toBeDisabled());
    await act(async () => { releasePost(http(body)); });
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('button', { name: 'Generating interpretation…' })).toBeDisabled();
    await act(async () => { releaseGet(http(body)); });
    expect(await screen.findByText(/Controlled synthetic stub assessment/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Generate AI Interpretation' })).toBeEnabled();
  });

  it.each([
    ['stage', (initial: ReturnType<typeof props>) => ({ ...initial, snapshot: snapshot('WORSENED') })],
    ['shipment', (initial: ReturnType<typeof props>) => ({ ...initial, anchor: { ...anchor, id: 143 }, snapshot: snapshot('CHANGE', 'B') })],
    ['epoch', (initial: ReturnType<typeof props>) => ({ ...initial, snapshot: { ...initial.snapshot, epochId: 'b7c40cb8-5afe-4860-9a7c-fb89c5230411' } })],
    ['snapshot', (initial: ReturnType<typeof props>) => ({ ...initial, snapshot: { ...initial.snapshot, snapshotId: '20c8bf47-d948-420f-9eaf-e3b5ba4b5098' } })],
    ['scenario session', (initial: ReturnType<typeof props>) => ({ ...initial, snapshot: { ...initial.snapshot, sessionId: '92f82d33-5726-4e8c-a5b5-60418403ffdc' } })],
    ['authenticated session', (initial: ReturnType<typeof props>) => ({ ...initial, authContextGeneration: 'auth-generation-2' })],
  ] as const)('hides a late response after %s changes while persisted retrieval is pending', async (_label, changeContext) => {
    const initial = props(snapshot('CHANGE'));
    let releaseGet!: (value: Response) => void;
    const fetch = vi.fn()
      .mockResolvedValueOnce(http(responseFor(initial.snapshot)))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { releaseGet = resolve; }));
    vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup();
    const view = render(<ScenarioAiInterpretation {...initial} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));

    view.rerender(<ScenarioAiInterpretation {...changeContext(initial)} />);
    expect(screen.queryByText(/Provider-generated assessment/)).not.toBeInTheDocument();
    await act(async () => { releaseGet(http(responseFor(initial.snapshot))); });
    expect(screen.queryByText(/Provider-generated assessment/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Generate AI Interpretation' })).toBeEnabled();
  });

  it('clears protected output and ends the login flow on 401', async () => {
    const user = userEvent.setup();
    const onSessionEnded = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(http({ code: 'UNAUTHENTICATED', message: 'Ended', retryable: false }, 401)));
    render(<ScenarioAiInterpretation {...props(snapshot(), { onSessionEnded })} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(onSessionEnded).toHaveBeenCalledOnce();
  });

  it('clears the interpretation and refreshes coordinator state after stale snapshot conflicts', async () => {
    const user = userEvent.setup();
    const onSnapshotStale = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(http({ code: 'STALE_REVISION', message: 'Stage changed.', retryable: false }, 409)));
    render(<ScenarioAiInterpretation {...props(snapshot(), { onSnapshotStale })} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Stage changed.');
    expect(onSnapshotStale).toHaveBeenCalledOnce();
    expect(screen.queryByText(/Provider-generated assessment/)).not.toBeInTheDocument();
  });

  it('clears scenario interpretation when the AI endpoint denies access', async () => {
    const user = userEvent.setup();
    const onAuthorizationLost = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(http({ code: 'SCENARIO_ACCESS_DENIED', message: 'Access denied.', retryable: false }, 403)));
    render(<ScenarioAiInterpretation {...props(snapshot(), { onAuthorizationLost })} />);
    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Access denied.');
    expect(onAuthorizationLost).toHaveBeenCalledOnce();
  });

  it('keeps retry available after a generation failure', async () => {
    const user = userEvent.setup();
    const fetch = vi.fn().mockResolvedValue(http({ code: 'PROVIDER_UNAVAILABLE', message: 'AI service is unavailable.', retryable: true }, 503));
    vi.stubGlobal('fetch', fetch);
    render(<ScenarioAiInterpretation {...props()} />);

    await user.click(screen.getByRole('button', { name: 'Generate AI Interpretation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('AI service is unavailable.');
    expect(screen.getByRole('button', { name: 'Generate AI Interpretation' })).toBeEnabled();
    expect(screen.queryByText(/Provider-generated assessment/)).not.toBeInTheDocument();
    expect(fetch).toHaveBeenCalledOnce();
  });

  it('explains when the explicit frontend gate is off without making a request', () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    render(<ScenarioAiInterpretation {...props()} enabled={false} />);
    const panel = screen.getByRole('region', { name: 'Scenario AI interpretation' });
    expect(panel).toHaveTextContent('AI interpretation is not enabled for this environment.');
    expect(panel).toHaveTextContent('Backend-calculated scenario facts and source evidence remain available. No AI request was made.');
    expect(fetch).not.toHaveBeenCalled();
  });
});
