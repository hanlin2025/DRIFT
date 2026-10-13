package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * A calculated mother-vessel arrival at the transshipment port. The estimate is not a confirmed arrival and is not stored.
 */
public record ArrivalEstimate(
		OffsetDateTime estimatedArrivalAt,
		String limitation,
		BigDecimal speedOverGroundKnots,
		Classification classification,
		String difference,
		Long differenceSeconds,
		Integer differenceNanos,
		Instant lastReceivedAt,
		Boolean possiblyOutdated,
		UnavailableReason unavailableReason) {

	public static final String LIMITATION =
			"Approximate arrival — based on straight-line distance and current speed.";

	public enum Classification {
		AHEAD_OF_PLAN,
		ON_SCHEDULE,
		BEHIND_PLAN
	}

	public record UnavailableReason(String code, String message) {
		public UnavailableReason {
			if (code == null || code.isBlank() || message == null || message.isBlank()) {
				throw new IllegalArgumentException("An unavailable reason needs a code and a message");
			}
		}
	}

	public ArrivalEstimate {
		boolean unavailable = unavailableReason != null;
		boolean populated = estimatedArrivalAt != null || limitation != null || speedOverGroundKnots != null
				|| classification != null || difference != null || differenceSeconds != null
				|| differenceNanos != null || lastReceivedAt != null || possiblyOutdated != null;
		if (unavailable && populated) {
			throw new IllegalArgumentException("An unavailable arrival estimate only carries its reason");
		}
		if (!unavailable && (estimatedArrivalAt == null || limitation == null || speedOverGroundKnots == null
				|| classification == null || difference == null || differenceSeconds == null
				|| differenceNanos == null || lastReceivedAt == null || possiblyOutdated == null)) {
			throw new IllegalArgumentException("An available arrival estimate needs every comparison field");
		}
	}

	static ArrivalEstimate available(OffsetDateTime estimatedArrivalAt, BigDecimal speedOverGroundKnots,
			Classification classification, String difference, long differenceSeconds, int differenceNanos,
			Instant lastReceivedAt, boolean possiblyOutdated) {
		return new ArrivalEstimate(estimatedArrivalAt, LIMITATION, speedOverGroundKnots, classification, difference,
				differenceSeconds, differenceNanos, lastReceivedAt, possiblyOutdated, null);
	}

	static ArrivalEstimate unavailable(String code, String message) {
		return new ArrivalEstimate(null, null, null, null, null, null, null, null, null,
				new UnavailableReason(code, message));
	}
}
