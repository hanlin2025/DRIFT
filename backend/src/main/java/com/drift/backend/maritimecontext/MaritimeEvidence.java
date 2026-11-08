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
		String limitation,
		String vesselIdentity,
		String vesselRole,
		String observationType,
		String associationScope) {
	/**
	 * Compatibility constructor for existing adapters. New role/type/scope facts remain unknown
	 * unless a source can substantiate them; callers must not infer them from a vessel IMO alone.
	 */
	public MaritimeEvidence(String sourceType, String sourceMode, String evidenceId, String title, String sourceIdentifier,
			String sourceUrl, String citation, String sourceEventAt, String observedAt, String retrievedAtUtc, String freshness,
			String previousValue, String currentValue, String changeMagnitude, String activityCompatibility,
			String locationCompatibility, String timeCompatibility, String compatibilityState, boolean evidenceSufficient,
			boolean humanReviewRequired, String shipmentAssociationConfidence, String limitation) {
		this(sourceType, sourceMode, evidenceId, title, sourceIdentifier, sourceUrl, citation, sourceEventAt, observedAt,
				retrievedAtUtc, freshness, previousValue, currentValue, changeMagnitude, activityCompatibility,
				locationCompatibility, timeCompatibility, compatibilityState, evidenceSufficient, humanReviewRequired,
				shipmentAssociationConfidence, limitation, null, null, null, "UNKNOWN");
	}
}
