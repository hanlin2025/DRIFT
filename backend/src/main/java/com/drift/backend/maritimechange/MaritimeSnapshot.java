package com.drift.backend.maritimechange;

/**
 * One curated maritime observation already normalized by the caller.
 * A collector directory is not a snapshot and is not read from here.
 */
public record MaritimeSnapshot(
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
		String previousValue,
		String currentValue,
		String activityCompatibility,
		String locationCompatibility,
		String timeCompatibility) {
}
