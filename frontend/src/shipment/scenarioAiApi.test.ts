import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  generateScenarioInterpretation, ScenarioAiApiError,
  type ScenarioInterpretationProvenance, type ScenarioInterpretationRequest,
} from './scenarioAiApi';

const SESSION_ID = 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf';
const EPOCH_ID = '74dab1c6-1d4b-4889-8dde-0b31986fed28';
const SNAPSHOT_ID = '55bb0888-2906-4ba0-946f-b054b0ef1272';
const CUTOFF = '2026-10-01T04:00:00Z';
const ASSESSMENT_IDS = {
  'BASELINE-A': '3574f541-396e-490a-addd-b426ce77f0eb',
  'BASELINE-B': 'bc72814a-4a53-461e-91af-2f094f79b89b',
  'CHANGE-A': 'a9172fc5-5cea-4348-8058-a27c40aa4eb1',
  'CHANGE-B': '39522c20-b6fb-4250-b3a7-8f546c217d7e',
  'WORSENED-A': '4592f5f1-6f45-40b6-8131-4d57ce43e6b1',
  'WORSENED-B': '32bb2f28-7608-4275-86cd-b570967ea34d',
  'MODEL_UNAVAILABLE': 'ef2bcacb-cc8e-4b2f-a512-1c12b7065eb8',
} as const;

type Stage = 'BASELINE' | 'CHANGE' | 'WORSENED';
function requestFor(scenarioShipmentKey: 'A' | 'B'): ScenarioInterpretationRequest {
  return { scenarioShipmentKey, expectedEpochId: EPOCH_ID, expectedRevision: 2,
    expectedSnapshotId: SNAPSHOT_ID, expectedSimulationCutoffUtc: CUTOFF };
}
function provenanceFor(stage: Stage, scenarioShipmentKey: 'A' | 'B'): ScenarioInterpretationProvenance {
  return { scenarioId: 'CDG279-Api', scenarioVersion: 'cdg279.synthetic-disruption.v1',
    scenarioShipmentReference: `DEMO-CDG279-Api-${scenarioShipmentKey}`, stage };
}

function assessment(stage: Stage, scenarioShipmentKey: 'A' | 'B', assessmentId: string,
  override: Record<string, unknown> = {}) {
  const abstained = stage === 'BASELINE' || override.validationStatus === 'MODEL_UNAVAILABLE';
  const validationStatus = override.validationStatus ?? (stage === 'BASELINE' ? 'INSUFFICIENT_EVIDENCE' : 'VALID');
  const interpretation = abstained ? null : {
    summary: `Synthetic ${stage.toLowerCase()} evidence warrants human review of the ${scenarioShipmentKey} shipment's arrival deadline.`,
    claims: [{
      statement: `The synthetic expected-arrival revision for shipment ${scenarioShipmentKey} warrants review alongside its backend-calculated deadline comparison.`,
      evidenceIds: ['cdg279-v1-revision-1'], applicability: 'Selected fictional scenario shipment only.',
      relationshipType: 'DELAY_COMPARISON_REVIEW', factReferences: ['business:DELAY_COMPARISON'], riskReview: null,
    }], uncertainties: ['The synthetic source does not establish a real-world delay.'],
    followUpConsiderations: ['Verify the cited revision and deadline with the operations team.'],
    evidenceSufficient: true, formalRecommendationCode: null,
  };
  return {
    assessmentId, state: abstained ? 'ABSTAINED' : 'GENERATED', validationStatus,
    bundleVersion: 'cdg246.maritime-evidence-bundle.v1', bundleHash: 'a'.repeat(64), assessmentCutoffUtc: CUTOFF,
    provider: 'offline-scenario-quality-stub', model: abstained ? '' : 'deterministic-fixture',
    promptVersion: 'cdg290.scenario-grounded-v1.5', generatedAtUtc: '2026-11-06T15:00:00Z',
    limitation: abstained ? (validationStatus === 'MODEL_UNAVAILABLE'
      ? 'The configured provider returned no interpretation.'
      : 'No sufficient, current synthetic evidence is available for inference.') : null,
    interpretation,
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
    ...override,
  };
}

function persistedResponse(stage: Stage, scenarioShipmentKey: 'A' | 'B', assessmentId: string,
  assessmentOverride: Record<string, unknown> = {}, override: Record<string, unknown> = {}) {
  return {
    sessionId: SESSION_ID, epochId: EPOCH_ID, snapshotId: SNAPSHOT_ID, revision: 2,
    simulationCutoffUtc: CUTOFF, scenarioShipmentKey, shipmentId: scenarioShipmentKey === 'A' ? 142 : 143,
    assessment: assessment(stage, scenarioShipmentKey, assessmentId, assessmentOverride),
    scenarioId: 'CDG279-Api', scenarioVersion: 'cdg279.synthetic-disruption.v1',
    scenarioShipmentReference: `DEMO-CDG279-Api-${scenarioShipmentKey}`, stage,
    evidenceHash: 'b'.repeat(64), contextHash: 'c'.repeat(64),
    ...override,
  };
}

function httpResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, headers: { get: () => 'application/json' }, json: async () => body } as unknown as Response;
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('scenario AI transport', () => {
  it.each([
    ['BASELINE', 'A', 'ABSTAINED', 'INSUFFICIENT_EVIDENCE'],
    ['BASELINE', 'B', 'ABSTAINED', 'INSUFFICIENT_EVIDENCE'],
    ['CHANGE', 'A', 'GENERATED', 'VALID'],
    ['CHANGE', 'B', 'GENERATED', 'VALID'],
    ['WORSENED', 'A', 'GENERATED', 'VALID'],
    ['WORSENED', 'B', 'GENERATED', 'VALID'],
  ] as const)('posts then retrieves persisted %s %s assessment with its UUID', async (stage, key, state, validationStatus) => {
    const id = ASSESSMENT_IDS[`${stage}-${key}` as keyof typeof ASSESSMENT_IDS];
    const body = persistedResponse(stage, key, id);
    const fetch = vi.fn().mockResolvedValueOnce(httpResponse(body)).mockResolvedValueOnce(httpResponse(body));
    vi.stubGlobal('fetch', fetch);

    const result = await generateScenarioInterpretation('secret-token', SESSION_ID, key === 'A' ? 142 : 143,
      requestFor(key), provenanceFor(stage, key));

    expect(result.assessment.assessmentId).toBe(id);
    expect(result.assessment.state).toBe(state);
    expect(result.assessment.validationStatus).toBe(validationStatus);
    expect(fetch).toHaveBeenCalledTimes(2);
    const [postPath, postInit] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(postPath).toBe(`/api/scenarios/v1/sessions/${SESSION_ID}/shipments/${key === 'A' ? 142 : 143}/interpretation`);
    expect(postInit.method).toBe('POST');
    expect(new Headers(postInit.headers).get('Authorization')).toBe('Bearer secret-token');
    expect(postInit.cache).toBe('no-store');
    expect(JSON.parse(String(postInit.body))).toEqual(requestFor(key));
    const [getPath, getInit] = fetch.mock.calls[1] as unknown as [string, RequestInit];
    expect(getPath).toBe(`/api/scenarios/v1/sessions/${SESSION_ID}/shipments/${key === 'A' ? 142 : 143}/assessments/${id}`);
    expect(getInit.method).toBe('GET');
    expect(new Headers(getInit.headers).get('Authorization')).toBe('Bearer secret-token');
    expect(getInit.cache).toBe('no-store');
  });

  it('uses same-origin cookie authentication for generation and retrieval when no bearer token is retained', async () => {
    const body = persistedResponse('WORSENED', 'A', ASSESSMENT_IDS['WORSENED-A']);
    const fetch = vi.fn().mockResolvedValueOnce(httpResponse(body)).mockResolvedValueOnce(httpResponse(body));
    vi.stubGlobal('fetch', fetch);
    await generateScenarioInterpretation('', SESSION_ID, 142, requestFor('A'), provenanceFor('WORSENED', 'A'));
    expect(fetch).toHaveBeenCalledTimes(2);
    for (const [, init] of fetch.mock.calls as unknown as [string, RequestInit][]) {
      expect(init.credentials).toBe('include');
      expect(new Headers(init.headers).get('X-DRIFT-Request')).toBe('1');
      expect(new Headers(init.headers).get('Authorization')).toBeNull();
    }
  });

  it('retrieves and presents a persisted MODEL_UNAVAILABLE abstention without fabricating an interpretation', async () => {
    const id = ASSESSMENT_IDS.MODEL_UNAVAILABLE;
    const body = persistedResponse('CHANGE', 'A', id, { validationStatus: 'MODEL_UNAVAILABLE' });
    const fetch = vi.fn().mockResolvedValueOnce(httpResponse(body)).mockResolvedValueOnce(httpResponse(body));
    vi.stubGlobal('fetch', fetch);

    const result = await generateScenarioInterpretation('token', SESSION_ID, 142,
      requestFor('A'), provenanceFor('CHANGE', 'A'));

    expect(result.assessment).toMatchObject({ assessmentId: id, state: 'ABSTAINED', validationStatus: 'MODEL_UNAVAILABLE', interpretation: null });
    expect(result.assessment.limitation).toContain('provider returned no interpretation');
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['scenario ID', { scenarioId: 'other-scenario' }],
    ['scenario version', { scenarioVersion: 'future-contract' }],
    ['fictional shipment reference', { scenarioShipmentReference: 'LIVE-SHIPMENT-142' }],
    ['stage', { stage: 'WORSENED' }],
    ['evidence hash', { evidenceHash: 'not-a-hash' }],
    ['context hash', { contextHash: 'D'.repeat(64) }],
    ['assessment UUID', { assessment: { ...assessment('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-A']), assessmentId: 'not-a-uuid' } }],
    ['assessment cutoff', { assessment: { ...assessment('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-A']), assessmentCutoffUtc: '2026-10-02T04:00:00Z' } }],
  ])('rejects malformed or mismatched %s provenance before retrieval', async (_label, override) => {
    const fetch = vi.fn().mockResolvedValueOnce(httpResponse(persistedResponse('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-A'], {}, override)));
    vi.stubGlobal('fetch', fetch);
    await expect(generateScenarioInterpretation('token', SESSION_ID, 142, requestFor('A'), provenanceFor('CHANGE', 'A')))
      .rejects.toMatchObject({ status: 502, backendCode: 'INVALID_SCENARIO_AI_RESPONSE' });
    expect(fetch).toHaveBeenCalledOnce();
  });

  it('rejects a saved response whose assessment UUID differs from the generated identity', async () => {
    const post = persistedResponse('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-A']);
    const get = persistedResponse('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-B']);
    const fetch = vi.fn().mockResolvedValueOnce(httpResponse(post)).mockResolvedValueOnce(httpResponse(get));
    vi.stubGlobal('fetch', fetch);
    await expect(generateScenarioInterpretation('token', SESSION_ID, 142, requestFor('A'), provenanceFor('CHANGE', 'A')))
      .rejects.toMatchObject({ status: 502, backendCode: 'INVALID_SCENARIO_AI_RESPONSE' });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['scenario provenance', { scenarioShipmentReference: 'DEMO-CDG279-Api-B' }],
    ['evidence hash', { evidenceHash: 'd'.repeat(64) }],
    ['context hash', { contextHash: 'e'.repeat(64) }],
    ['assessment identity', { assessment: { ...assessment('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-B']) } }],
  ])('rejects a persisted GET with a changed %s after a valid POST', async (_label, override) => {
    const post = persistedResponse('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-A']);
    const get = persistedResponse('CHANGE', 'A', ASSESSMENT_IDS['CHANGE-A'], {}, override);
    const fetch = vi.fn().mockResolvedValueOnce(httpResponse(post)).mockResolvedValueOnce(httpResponse(get));
    vi.stubGlobal('fetch', fetch);
    await expect(generateScenarioInterpretation('token', SESSION_ID, 142, requestFor('A'), provenanceFor('CHANGE', 'A')))
      .rejects.toMatchObject({ status: 502, backendCode: 'INVALID_SCENARIO_AI_RESPONSE' });
  });

  it('preserves stable disabled/provider error codes without retrying', async () => {
    const fetch = vi.fn().mockResolvedValue(httpResponse({ code: 'SCENARIO_AI_DISABLED', message: 'Scenario AI is disabled.', retryable: false }, 404));
    vi.stubGlobal('fetch', fetch);
    await expect(generateScenarioInterpretation('token', SESSION_ID, 142, requestFor('A'), provenanceFor('CHANGE', 'A')))
      .rejects.toMatchObject({ backendCode: 'SCENARIO_AI_DISABLED', status: 404, retryable: false });
    expect(fetch).toHaveBeenCalledOnce();
  });
});
