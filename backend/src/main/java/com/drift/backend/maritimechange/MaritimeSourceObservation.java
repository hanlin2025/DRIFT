package com.drift.backend.maritimechange;

/** One source record before it is mapped into the persisted snapshot/change contract. */
record MaritimeSourceObservation(
		String sourceType,
		String sourceIdentity,
		String provenance,
		String sourceEventAt,
		String observedAt,
		String retrievedAtUtc,
		String sourceMode,
		String freshness,
		String vesselImo,
		String location,
		String effectiveFrom,
		String effectiveUntil,
		String currentValue,
		String activityCompatibility,
		String locationCompatibility,
		String timeCompatibility) {
}
