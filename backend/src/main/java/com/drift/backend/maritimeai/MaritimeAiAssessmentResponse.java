package com.drift.backend.maritimeai;

public record MaritimeAiAssessmentResponse(String assessmentId, String state, String validationStatus,
		String bundleVersion, String bundleHash, String assessmentCutoffUtc, String provider, String model,
		String promptVersion, String generatedAtUtc, MaritimeAiInterpretation interpretation, String limitation,
		MaritimeAiDeterministicSummary deterministicSummary) {
	/** Existing callers and persisted API fixtures remain source-compatible. */
	public MaritimeAiAssessmentResponse(String assessmentId, String state, String validationStatus,
			String bundleVersion, String bundleHash, String assessmentCutoffUtc, String provider, String model,
			String promptVersion, String generatedAtUtc, MaritimeAiInterpretation interpretation, String limitation) {
		this(assessmentId, state, validationStatus, bundleVersion, bundleHash, assessmentCutoffUtc, provider, model,
				promptVersion, generatedAtUtc, interpretation, limitation, null);
	}
}
