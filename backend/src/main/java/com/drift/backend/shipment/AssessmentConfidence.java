package com.drift.backend.shipment;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * How far one read of the connection-risk assessment can be trusted.
 * This is not the risk level. Not stored.
 * A completed assessment has a confidence and no unavailable reasons.
 * A missing planned time has only the unavailable reasons.
 */
record AssessmentConfidence(String confidence, List<String> unavailable) {

	private static final String ESTIMATED_ARRIVAL = "The estimated arrival was used.";
	private static final String LIMITED = "Confidence is limited because the planned connection window is being used.";
	private static final String MISSING_MOTHER =
			"The assessment is unavailable because the planned mother-vessel arrival is missing.";
	private static final String MISSING_FEEDER =
			"The assessment is unavailable because the planned feeder-vessel departure is missing.";

	AssessmentConfidence {
		if (unavailable == null) {
			if (!ESTIMATED_ARRIVAL.equals(confidence) && !LIMITED.equals(confidence)) {
				throw new IllegalArgumentException("A completed assessment needs one recorded confidence");
			}
		} else if (confidence != null || unavailable.isEmpty()
				|| unavailable.stream().anyMatch(reason -> reason == null || reason.isBlank())) {
			throw new IllegalArgumentException("An unavailable assessment has only its reasons");
		} else {
			unavailable = List.copyOf(unavailable);
		}
	}

	static AssessmentConfidence of(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining,
			OffsetDateTime plannedMotherArrivalAt, OffsetDateTime plannedFeederDepartureAt) {
		if (planned == null) {
			throw new IllegalArgumentException("A planned connection window calculation is required");
		}
		if (remaining != null) {
			return new AssessmentConfidence(ESTIMATED_ARRIVAL, null);
		}
		if (planned.status() == ConnectionWindowCalculation.Status.INCOMPLETE) {
			return new AssessmentConfidence(null, missing(plannedMotherArrivalAt, plannedFeederDepartureAt));
		}
		return new AssessmentConfidence(LIMITED, null);
	}

	private static List<String> missing(OffsetDateTime plannedMotherArrivalAt, OffsetDateTime plannedFeederDepartureAt) {
		List<String> reasons = new ArrayList<>();
		if (plannedMotherArrivalAt == null) {
			reasons.add(MISSING_MOTHER);
		}
		if (plannedFeederDepartureAt == null) {
			reasons.add(MISSING_FEEDER);
		}
		if (reasons.isEmpty()) {
			throw new IllegalArgumentException("An incomplete planned window needs a missing planned time");
		}
		return reasons;
	}
}
