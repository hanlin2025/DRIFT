package com.drift.backend.maritimechange;

import java.time.Instant;

/**
 * One meaningful new or revised observation. A repeated fingerprint does not produce another one.
 * Source-event text stays in its source representation. {@code retrievedAtUtc} is the UTC retrieval instant.
 */
public record MaritimeSnapshotChange(
		String sourceType,
		String sourceIdentity,
		String contentFingerprint,
		String kind,
		String ordering,
		boolean forwardLooking,
		String provenance,
		String sourceEventAt,
		String observedAt,
		String retrievedAtUtc,
		Instant retrievedAt,
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
		String timeCompatibility,
		Instant recordedAt) {
}
