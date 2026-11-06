package com.drift.backend.maritimeai;

/** Validated output from the shared maritime-AI pipeline, before scenario persistence. */
record MaritimeAiScenarioGenerationResult(
		MaritimeAiClient.MaritimeAiClientResult clientResult,
		MaritimeAiGenerationState state,
		String validationStatus,
		MaritimeAiInterpretation interpretation,
		String limitation) { }
