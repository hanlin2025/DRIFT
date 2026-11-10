package com.drift.backend.maritimechange;

import java.util.List;

/** Shipment-authorized, read-only changes and collector availability. */
public record MaritimeChangeEventResponse(String availability, List<Event> events, String nextCursor) {
	public record Event(
			String id,
			String sourceType,
			String sourceIdentity,
			String eventType,
			String sourceEventTime,
			String observedAt,
			String retrievedAt,
			String detectedAt,
			String previousValue,
			String currentValue,
			String provenance,
			String sourceMode,
			String freshness,
			String associationConfidence,
			Compatibility compatibility,
			boolean requiresHumanReview) {
	}

	public record Compatibility(String activity, String location, String time) {
	}
}
