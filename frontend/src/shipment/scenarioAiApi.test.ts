import { afterEach, describe, expect, it, vi } from 'vitest';
import { generateScenarioInterpretation, ScenarioAiApiError, type ScenarioInterpretationRequest } from './scenarioAiApi';

const request: ScenarioInterpretationRequest = {
  scenarioShipmentKey: 'A', expectedEpochId: '74dab1c6-1d4b-4889-8dde-0b31986fed28', expectedRevision: 2,
  expectedSnapshotId: '55bb0888-2906-4ba0-946f-b054b0ef1272', expectedSimulationCutoffUtc: '2026-10-01T04:00:00Z',
};
const assessment = {
  assessmentId: null, state: 'GENERATED', validationStatus: 'VALID', bundleVersion: 'cdg246.maritime-evidence-bundle.v1',
  bundleHash: 'a'.repeat(64), assessmentCutoffUtc: request.expectedSimulationCutoffUtc,
  provider: 'openai-responses', model: 'test-model', promptVersion: 'cdg290.scenario-grounded-v1.1',
  generatedAtUtc: '2026-11-03T00:00:00Z', limitation: null,
  interpretation: { summary: 'Review the grounded interpretation and limitations.', claims: [], uncertainties: [],
    followUpConsiderations: ['Ask the operations team to verify current evidence.'], evidenceSufficient: true, formalRecommendationCode: null },
  deterministicSummary: { summaryVersion: 'v1', label: 'Backend deterministic facts', shipmentScheduleFacts: [], evidenceFacts: [], limitations: [] },
};
function response(overrides: Record<string, unknown> = {}) {
  return {
    sessionId: 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf', epochId: request.expectedEpochId,
    snapshotId: request.expectedSnapshotId, revision: request.expectedRevision,
    simulationCutoffUtc: request.expectedSimulationCutoffUtc, scenarioShipmentKey: 'A', shipmentId: 142,
    assessment, ...overrides,
  };
}
function httpResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, headers: { get: () => 'application/json' }, json: async () => body } as unknown as Response;
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('scenario AI transport', () => {
  it('posts the exact selected snapshot binding with bearer auth and no-store semantics', async () => {
    const fetch = vi.fn().mockResolvedValue(httpResponse(response()));
    vi.stubGlobal('fetch', fetch);
    const result = await generateScenarioInterpretation('secret-token', 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf', 142, request);

    expect(result.assessment.state).toBe('GENERATED');
    expect(fetch).toHaveBeenCalledOnce();
    const [path, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/scenarios/v1/sessions/d5f2390b-d610-4c5d-9b6c-7153f8a89fbf/shipments/142/interpretation');
    expect(init.method).toBe('POST');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer secret-token');
    expect(init.cache).toBe('no-store');
    expect(JSON.parse(String(init.body))).toEqual(request);
  });

  it('rejects response identity mismatches and malformed transient AI envelopes', async () => {
    const fetch = vi.fn().mockResolvedValue(httpResponse(response({ scenarioShipmentKey: 'B' })));
    vi.stubGlobal('fetch', fetch);
    await expect(generateScenarioInterpretation('token', 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf', 142, request))
      .rejects.toMatchObject({ status: 502, backendCode: 'INVALID_SCENARIO_AI_RESPONSE' });
  });

  it('preserves stable disabled and provider error codes without retrying', async () => {
    const fetch = vi.fn().mockResolvedValue(httpResponse({ code: 'SCENARIO_AI_DISABLED', message: 'Scenario AI is disabled.', retryable: false }, 404));
    vi.stubGlobal('fetch', fetch);
    try {
      await generateScenarioInterpretation('token', 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf', 142, request);
      throw new Error('expected API error');
    } catch (error) {
      expect(error).toBeInstanceOf(ScenarioAiApiError);
      expect(error).toMatchObject({ backendCode: 'SCENARIO_AI_DISABLED', status: 404, retryable: false });
    }
    expect(fetch).toHaveBeenCalledOnce();
  });
});
