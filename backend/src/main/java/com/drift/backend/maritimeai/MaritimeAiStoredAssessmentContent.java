package com.drift.backend.maritimeai;

/** Versioned persistence envelope; old rows contain MaritimeAiInterpretation directly. */
record MaritimeAiStoredAssessmentContent(MaritimeAiInterpretation interpretation,
		MaritimeAiDeterministicSummary deterministicSummary, MaritimeAiAssessmentHistoryScope historyScope) {
	MaritimeAiStoredAssessmentContent(MaritimeAiInterpretation interpretation,
			MaritimeAiDeterministicSummary deterministicSummary) {
		this(interpretation, deterministicSummary, null);
	}
}
