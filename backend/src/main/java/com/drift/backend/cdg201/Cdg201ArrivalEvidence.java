package com.drift.backend.cdg201;

/**
 * Read-only replay of an externally collected MPA expected-arrival revision.
 * Raw source strings and derived Singapore/UTC instants are both preserved.
 * This is not an ArrivalEstimate and does not carry a risk.
 */
public record Cdg201ArrivalEvidence(
		String contractVersion,
		String evidenceState,
		String vesselName,
		String vesselImo,
		String previousExpectedArrival,
		String revisedExpectedArrival,
		String previousExpectedArrivalSgt,
		String previousExpectedArrivalUtc,
		String revisedExpectedArrivalSgt,
		String revisedExpectedArrivalUtc,
		int revisionMinutes,
		String previousCollectionUtc,
		String latestCollectionUtc,
		String previousCollectionRunId,
		String latestCollectionRunId,
		String previousSourceManifest,
		String previousSourceRawFile,
		String sourceManifest,
		String sourceRawFile,
		String confidence,
		String ambiguity,
		String sourceTimestampSemantics,
		String locationFrom,
		String locationTo,
		String locationMappingStatus,
		String shipmentAssociation,
		String limitations,
		Cdg201SimulatedConnectionScenario simulatedScenario) {
}
