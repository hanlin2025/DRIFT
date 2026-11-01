package com.drift.backend.shipment;

import java.util.List;

/**
 * Rules applied to the connection-risk assessment on one read.
 * A rule is not a measurement. Not stored.
 * Null when a planned time is missing and there is no risk.
 */
final class AssessmentAssumptions {

	private static final String STRAIGHT_LINE = "The estimated arrival uses a straight-line distance.";
	private static final String SPEED_RULE = "A speed of at least 0.5 knots can be used.";
	private static final String FRESHNESS_RULE = "A position up to six hours old can be used.";
	private static final String PLANNED_FALLBACK =
			"The planned connection window is used because the estimated arrival could not be used.";
	private static final String HOUR_BANDS =
			"Under 12 hours is High, under 24 hours is Medium, and 24 hours or more is Low. Exactly 12 hours is Medium and exactly 24 hours is Low. A missed or non-positive window is Critical.";

	private AssessmentAssumptions() {
	}

	static List<String> of(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining) {
		if (planned == null) {
			throw new IllegalArgumentException("A planned connection window calculation is required");
		}
		if (remaining != null) {
			return List.of(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);
		}
		if (planned.status() == ConnectionWindowCalculation.Status.INCOMPLETE) {
			return null;
		}
		return List.of(PLANNED_FALLBACK, HOUR_BANDS);
	}
}
