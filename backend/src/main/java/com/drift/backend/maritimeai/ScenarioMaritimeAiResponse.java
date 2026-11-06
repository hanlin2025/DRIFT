package com.drift.backend.maritimeai;

import java.util.UUID;

/** Response paired with the exact scenario context that produced the persisted assessment. */
public record ScenarioMaritimeAiResponse(
		UUID sessionId,
		UUID epochId,
		UUID snapshotId,
		long revision,
		String simulationCutoffUtc,
		String scenarioShipmentKey,
		Long shipmentId,
		MaritimeAiAssessmentResponse assessment,
		String scenarioId,
		String scenarioVersion,
		String scenarioShipmentReference,
		String stage,
		String evidenceHash,
		String contextHash) {
	public ScenarioMaritimeAiResponse(UUID sessionId, UUID epochId, UUID snapshotId, long revision,
			String simulationCutoffUtc, String scenarioShipmentKey, Long shipmentId,
			MaritimeAiAssessmentResponse assessment) {
		this(sessionId, epochId, snapshotId, revision, simulationCutoffUtc, scenarioShipmentKey, shipmentId,
				assessment, null, null, null, null, null, null);
	}
}
