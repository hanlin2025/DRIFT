package com.drift.backend.maritimecontext;

/**
 * A single read-only item in the maritime-context bundle.  Every timestamp is
 * deliberately kept in its source representation: sourceEventAt is not
 * silently substituted for the UTC retrieval time used for point-in-time
 * filtering.
 */
public record MaritimeEvidence(
		String sourceType,
		String sourceMode,
		String evidenceId,
		String title,
		String sourceIdentifier,
		String sourceUrl,
		String citation,
		String sourceEventAt,
		String observedAt,
		String retrievedAtUtc,
		String freshness,
		String previousValue,
		String currentValue,
		String changeMagnitude,
		String activityCompatibility,
		String locationCompatibility,
		String timeCompatibility,
		String compatibilityState,
		boolean evidenceSufficient,
		boolean humanReviewRequired,
		String shipmentAssociationConfidence,
		String limitation) {
}
