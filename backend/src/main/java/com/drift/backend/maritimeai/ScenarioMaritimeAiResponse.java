package com.drift.backend.maritimeai;

import java.util.UUID;

/** Transient response paired with the exact scenario context that produced it. */
public record ScenarioMaritimeAiResponse(
		UUID sessionId,
		UUID epochId,
		UUID snapshotId,
		long revision,
		String simulationCutoffUtc,
		String scenarioShipmentKey,
		Long shipmentId,
		MaritimeAiAssessmentResponse assessment) { }
