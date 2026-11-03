package com.drift.backend.maritimechange;

import java.time.Instant;

/**
 * One current stored observation that the matcher kept for one shipment. The association and the
 * compatibility values are the matcher's, so a regional weather reading stays regional context.
 */
public record MaritimeSignal(
		long shipmentId,
		String sourceType,
		String sourceIdentity,
		String association,
		String vesselRole,
		String timeCompatibility,
		String location,
		String previousValue,
		String currentValue,
		String sourceEventAt,
		String effectiveFrom,
		String effectiveUntil,
		Instant retrievedAt,
		String provenance) {

	public static final String MPA_ETA_REVISION = MaritimeSnapshotChangeDetector.MPA_ETA_REVISION;
	public static final String MPA_OFFICIAL_NOTICE = MaritimeSnapshotChangeDetector.MPA_OFFICIAL_NOTICE;
	public static final String NEA_WEATHER = MaritimeSnapshotChangeDetector.NEA_WEATHER;
	public static final String VERIFIED_ASSOCIATION = MaritimeSnapshotShipmentMatcher.VERIFIED_ASSOCIATION;
	public static final String REGIONAL_CONTEXT = MaritimeSnapshotShipmentMatcher.REGIONAL_CONTEXT;
	public static final String UNCONFIRMED_CONTEXT = MaritimeSnapshotShipmentMatcher.UNCONFIRMED_CONTEXT;
	public static final String COMPATIBLE = MaritimeSnapshotChangeDetector.COMPATIBLE;
}
