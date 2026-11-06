import { useEffect, useMemo, useRef, useState } from 'react';
import type { Session } from '../session/session';
import type { Shipment, MaritimeAiAssessment } from './api';
import { MaritimeAiAssessmentPanel } from './MaritimeAiAssessmentPanel';
import type { DecodedScenarioSnapshot } from './scenarioDomain';
import { generateScenarioInterpretation, ScenarioAiApiError } from './scenarioAiApi';

type StoredResult = Readonly<{ contextKey: string; assessment: MaritimeAiAssessment }>;
type StoredError = Readonly<{ contextKey: string; message: string }>;

export type ScenarioAiInterpretationProps = Readonly<{
  enabled: boolean;
  viewer: Session;
  anchor: Shipment;
  authContextGeneration: string;
  snapshot: DecodedScenarioSnapshot;
  onSessionEnded: () => void;
  onSnapshotStale: () => void;
  onAuthorizationLost: () => void;
}>;

/** Explicit button-only scenario inference; no history read, stage effect, or automatic retry. */
export function ScenarioAiInterpretation({ enabled, viewer, anchor, authContextGeneration, snapshot,
  onSessionEnded, onSnapshotStale, onAuthorizationLost }: ScenarioAiInterpretationProps) {
  const contextKey = useMemo(() => JSON.stringify([
    viewer.id, viewer.company?.id ?? null, authContextGeneration, anchor.id,
    snapshot.sessionId, snapshot.epochId, snapshot.snapshotId, snapshot.revision,
    snapshot.selectedShipment.scenarioShipmentKey, snapshot.simulationCutoffUtc,
  ]), [viewer.id, viewer.company?.id, authContextGeneration, anchor.id, snapshot]);
  const currentKey = useRef(contextKey);
  currentKey.current = contextKey;
  const abortRef = useRef<AbortController | null>(null);
  const [result, setResult] = useState<StoredResult | null>(null);
  const [failure, setFailure] = useState<StoredError | null>(null);
  const [loadingKey, setLoadingKey] = useState<string | null>(null);

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setResult(current => current?.contextKey === contextKey ? current : null);
    setFailure(current => current?.contextKey === contextKey ? current : null);
    setLoadingKey(current => current === contextKey ? current : null);
    return () => abortRef.current?.abort();
  }, [contextKey]);

  if (!enabled) return null;
  const assessment = result?.contextKey === contextKey ? result.assessment : null;
  const error = failure?.contextKey === contextKey ? failure.message : null;
  const generating = loadingKey === contextKey;

  async function generate() {
    if (generating || !enabled) return;
    const controller = new AbortController();
    abortRef.current?.abort();
    abortRef.current = controller;
    setLoadingKey(contextKey);
    setFailure(null);
    try {
      const response = await generateScenarioInterpretation(viewer.token, snapshot.sessionId, anchor.id, {
        scenarioShipmentKey: snapshot.selectedShipment.scenarioShipmentKey,
        expectedEpochId: snapshot.epochId,
        expectedRevision: snapshot.revision,
        expectedSnapshotId: snapshot.snapshotId,
        expectedSimulationCutoffUtc: snapshot.simulationCutoffUtc,
      }, controller.signal);
      if (currentKey.current !== contextKey || controller.signal.aborted) return;
      setResult({ contextKey, assessment: response.assessment });
    } catch (cause) {
      if (controller.signal.aborted || currentKey.current !== contextKey) return;
      if (cause instanceof ScenarioAiApiError) {
        if (cause.status === 401) {
          setResult(null);
          onSessionEnded();
        } else if (cause.backendCode === 'STALE_EPOCH' || cause.backendCode === 'STALE_REVISION'
          || cause.backendCode === 'STALE_SNAPSHOT' || cause.backendCode === 'SCENARIO_ASSESSMENT_NOT_FOUND') {
          setResult(null);
          onSnapshotStale();
        } else if (cause.status === 403 || (cause.status === 404 && cause.backendCode !== 'SCENARIO_AI_DISABLED')) {
          setResult(null);
          onAuthorizationLost();
        }
      }
      setFailure({ contextKey, message: cause instanceof Error ? cause.message : 'Scenario interpretation could not be completed.' });
    } finally {
      if (currentKey.current === contextKey) setLoadingKey(current => current === contextKey ? null : current);
    }
  }

  return <MaritimeAiAssessmentPanel assessments={assessment ? [assessment] : []} unavailable={false}
    generating={generating} onGenerate={() => { void generate(); }} scenarioMode generationError={error} />;
}
