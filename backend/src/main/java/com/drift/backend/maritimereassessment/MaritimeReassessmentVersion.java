package com.drift.backend.maritimereassessment;

import java.time.Instant;
import java.util.List;

/** One stored reassessment, newest or validated, without replacing the raw persisted output. */
public record MaritimeReassessmentVersion(
		String assessmentId,
		String bundleVersion,
		String bundleHash,
		List<String> snapshotIds,
		String sourceFreshness,
		boolean forwardLooking,
		Instant assessmentCutoffUtc,
		Instant generatedAt,
		String provider,
		String model,
		String promptVersion,
		String generationState,
		String validationStatus,
		String interpretation,
		String failureMetadata,
		String outputJson) {

	static MaritimeReassessmentVersion from(StoredMaritimeReassessment stored) {
		MaritimeReassessmentLinkage linkage = MaritimeReassessmentLinkage.read(stored.outputJson());
		boolean validated = validated(stored.generationState(), stored.validationStatus());
		return new MaritimeReassessmentVersion(stored.assessmentId(), stored.bundleVersion(), stored.bundleHash(),
				linkage.snapshotIds(), linkage.sourceFreshness(), linkage.forwardLooking(), stored.cutoff(),
				stored.createdAt(), stored.provider(), stored.model(), stored.promptVersion(), stored.generationState(),
				stored.validationStatus(), validated ? linkage.interpretation() : null, stored.failureMetadata(),
				stored.outputJson());
	}

	static boolean validated(String generationState, String validationStatus) {
		return "GENERATED".equals(generationState) && "VALID".equals(validationStatus);
	}

	public boolean validated() {
		return validated(generationState, validationStatus);
	}
}
