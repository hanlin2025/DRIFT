package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.util.List;

/**
 * Facts observed for the connection-risk assessment on one read.
 * Uses the window, duration, position time, and speed already calculated for that read. Not stored.
 * Null when a planned time is missing and there is no risk.
 */
final class AssessmentEvidence {

	private static final String REMAINING_WINDOW = "Remaining connection window";
	private static final String PLANNED_WINDOW = "Planned connection window";
	private static final String NOT_POSITIVE = "The planned connection window is not positive.";

	private AssessmentEvidence() {
	}

	static List<String> of(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining,
			ArrivalEstimate estimate) {
		if (planned == null) {
			throw new IllegalArgumentException("A planned connection window calculation is required");
		}
		if (remaining != null) {
			return remainingEvidence(remaining, estimate);
		}
		if (planned.status() == ConnectionWindowCalculation.Status.INCOMPLETE) {
			return null;
		}
		return plannedEvidence(planned, estimate);
	}

	private static List<String> remainingEvidence(RemainingConnectionWindow remaining, ArrivalEstimate estimate) {
		String observed = switch (remaining.status()) {
			case AVAILABLE -> remaining.duration();
			case MISSED -> "The connection is expected to be missed by " + remaining.missedBy() + ".";
		};
		return List.of(REMAINING_WINDOW, observed, positionAndSpeed(estimate));
	}

	private static List<String> plannedEvidence(ConnectionWindowCalculation planned, ArrivalEstimate estimate) {
		String observed = switch (planned.status()) {
			case AVAILABLE -> planned.window().duration();
			case INVALID -> NOT_POSITIVE;
			case INCOMPLETE -> throw new IllegalArgumentException("A missing planned time has no evidence");
		};
		return List.of(PLANNED_WINDOW, observed, reason(estimate));
	}

	private static String positionAndSpeed(ArrivalEstimate estimate) {
		if (estimate == null || estimate.lastReceivedAt() == null || estimate.speedOverGroundKnots() == null) {
			throw new IllegalArgumentException("A remaining window needs the position time and speed that were used");
		}
		return "Position received " + estimate.lastReceivedAt() + ". Speed used " + knots(estimate.speedOverGroundKnots())
				+ " knots.";
	}

	private static String knots(BigDecimal speed) {
		return speed.stripTrailingZeros().toPlainString();
	}

	private static String reason(ArrivalEstimate estimate) {
		if (estimate != null && estimate.unavailableReason() != null) {
			return switch (estimate.unavailableReason().code()) {
				case ArrivalEstimateCalculator.NO_POSITION -> "No AIS position has been received for the mother vessel.";
				case ArrivalEstimateCalculator.INVALID_COORDINATES -> "The vessel or port coordinates cannot be used.";
				case ArrivalEstimateCalculator.MISSING_PORT -> "The transshipment port is missing.";
				case ArrivalEstimateCalculator.UNKNOWN_PORT -> "The transshipment port is not on the route map.";
				case ArrivalEstimateCalculator.UNUSABLE_SPEED -> "The speed is missing or below 0.5 knots.";
				default -> throw new IllegalArgumentException("The estimate reason is outside the recorded set");
			};
		}
		if (estimate != null && Boolean.TRUE.equals(estimate.possiblyOutdated())) {
			return "The position is more than six hours old.";
		}
		throw new IllegalArgumentException("A planned-window assessment needs the reason the estimate was not used");
	}
}
