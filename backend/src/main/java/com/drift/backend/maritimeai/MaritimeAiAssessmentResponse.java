package com.drift.backend.maritimeai;

public record MaritimeAiAssessmentResponse(String assessmentId, String state, String validationStatus,
		String bundleVersion, String bundleHash, String assessmentCutoffUtc, String provider, String model,
		String promptVersion, String generatedAtUtc, MaritimeAiInterpretation interpretation, String limitation) { }
