package com.drift.backend.maritimechange;

import java.time.OffsetDateTime;

/** A LIVE MPA arrival revision whose source time and shipment visit match are verified. */
public record VerifiedMpaArrival(OffsetDateTime expectedArrivalAt, String sourceIdentity, String sourceEventAt,
		String retrievedAtUtc, String provenance) {
	public VerifiedMpaArrival {
		if (expectedArrivalAt == null || sourceIdentity == null || sourceIdentity.isBlank()
				|| sourceEventAt == null || sourceEventAt.isBlank() || retrievedAtUtc == null || retrievedAtUtc.isBlank()
				|| provenance == null || provenance.isBlank()) {
			throw new IllegalArgumentException("A verified MPA arrival needs its value and source identity/times");
		}
	}
}
