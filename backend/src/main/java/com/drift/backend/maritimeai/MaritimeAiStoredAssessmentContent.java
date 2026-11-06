package com.drift.backend.maritimeai;

/** Versioned persistence envelope; old rows contain MaritimeAiInterpretation directly. */
record MaritimeAiStoredAssessmentContent(MaritimeAiInterpretation interpretation,
		MaritimeAiDeterministicSummary deterministicSummary, MaritimeAiAssessmentHistoryScope historyScope,
		String recordType, MaritimeAiScenarioAssessmentContext scenarioContext) {
	static final String SHIPMENT_ASSESSMENT = "SHIPMENT_ASSESSMENT";
	static final String CONTROLLED_SCENARIO_ASSESSMENT = "CONTROLLED_SCENARIO_ASSESSMENT";

	MaritimeAiStoredAssessmentContent(MaritimeAiInterpretation interpretation,
			MaritimeAiDeterministicSummary deterministicSummary, MaritimeAiAssessmentHistoryScope historyScope) {
		this(interpretation, deterministicSummary, historyScope, SHIPMENT_ASSESSMENT, null);
	}

	MaritimeAiStoredAssessmentContent(MaritimeAiInterpretation interpretation,
			MaritimeAiDeterministicSummary deterministicSummary) {
		this(interpretation, deterministicSummary, null, SHIPMENT_ASSESSMENT, null);
	}
}
