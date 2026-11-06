package com.drift.backend.maritimereassessment;

import java.time.Duration;
import java.util.List;

/**
 * Minimised evidence sent to a provider. It has no shipment reference, company, schedule, or credential.
 */
public record MaritimeProviderRequest(
		String datasetId,
		List<String> snapshotIds,
		String associationCeiling,
		List<MaritimeProviderEvidence> evidence,
		Duration timeout) {

	public record MaritimeProviderEvidence(
			String sourceType,
			String sourceMode,
			String evidenceId,
			String freshness,
			String previousValue,
			String currentValue,
			String activityCompatibility,
			String locationCompatibility,
			String timeCompatibility,
			String compatibilityState,
			String shipmentAssociationConfidence,
			String limitation) {
	}
}
