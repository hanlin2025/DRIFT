package com.drift.backend.maritimereassessment;

import java.time.Duration;
import java.util.Set;

/** Bounds for one reassessment. External processing stays closed unless every gate is set. */
public record MaritimeReassessmentLimits(
		boolean externalProcessingEnabled,
		Set<String> approvedDatasets,
		Set<String> approvedSourceTypes,
		int maxConcurrentRequests,
		int maxCallsPerMinute,
		int maxEstimatedCostUnits,
		Duration callTimeout,
		int maxAttempts) {

	public MaritimeReassessmentLimits {
		approvedDatasets = approvedDatasets == null ? Set.of() : Set.copyOf(approvedDatasets);
		approvedSourceTypes = approvedSourceTypes == null ? Set.of() : Set.copyOf(approvedSourceTypes);
		if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()) {
			throw new IllegalArgumentException("Provider timeout must be positive");
		}
	}

	static MaritimeReassessmentLimits closed() {
		return new MaritimeReassessmentLimits(false, Set.of(), Set.of(), 1, 2, 1, Duration.ofSeconds(2), 2);
	}
}
