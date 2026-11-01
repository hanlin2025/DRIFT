package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Evidence, assumptions, and confidence for the connection-risk assessment on this read. Calculated when the shipment is read and not stored. A completed assessment has evidence, assumptions, and confidence. An unavailable assessment has only the unavailable reasons and does not return a limited confidence.")
public record AssessmentExplanation(
		@Schema(description = "What this read observed for the window in use. Null when the assessment is unavailable.", nullable = true)
		List<String> evidence,
		@Schema(description = "Rules applied on this read. Null when the assessment is unavailable.", nullable = true)
		List<String> assumptions,
		@Schema(description = "How far this completed assessment can be trusted. This is not the risk level. Null when the assessment is unavailable.", example = "The estimated arrival was used.", nullable = true)
		String confidence,
		@Schema(description = "Why there is no assessment. Null when the assessment was completed. A missing planned mother-vessel arrival is named before a missing planned feeder-vessel departure.", nullable = true)
		List<String> unavailable) {

	private static final String ESTIMATED_ARRIVAL = "The estimated arrival was used.";
	private static final String LIMITED = "Confidence is limited because the planned connection window is being used.";

	private static final String REMAINING_WINDOW = "Remaining connection window";
	private static final String PLANNED_WINDOW = "Planned connection window";
	private static final String STRAIGHT_LINE = "The estimated arrival uses a straight-line distance.";
	private static final String SPEED_RULE = "A speed of at least 0.5 knots can be used.";
	private static final String FRESHNESS_RULE = "A position up to six hours old can be used.";
	private static final String PLANNED_FALLBACK =
			"The planned connection window is used because the estimated arrival could not be used.";
	private static final String HOUR_BANDS =
			"Under 12 hours is High, under 24 hours is Medium, and 24 hours or more is Low. Exactly 12 hours is Medium and exactly 24 hours is Low. A missed or non-positive window is Critical.";
	private static final String NOT_POSITIVE = "The planned connection window is not positive.";
	private static final String MISSING_MOTHER =
			"The assessment is unavailable because the planned mother-vessel arrival is missing.";
	private static final String MISSING_FEEDER =
			"The assessment is unavailable because the planned feeder-vessel departure is missing.";

	public AssessmentExplanation {
		if (unavailable == null) {
			if (evidence == null || evidence.isEmpty() || assumptions == null || assumptions.isEmpty()
					|| confidence == null || confidence.isBlank()) {
				throw new IllegalArgumentException("A completed assessment needs evidence, assumptions, and confidence");
			}
			evidence = List.copyOf(evidence);
			assumptions = List.copyOf(assumptions);
		} else if (evidence != null || assumptions != null || confidence != null || unavailable.isEmpty()) {
			throw new IllegalArgumentException("An unavailable assessment has only its reasons");
		} else {
			unavailable = List.copyOf(unavailable);
		}
	}

	static AssessmentExplanation of(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining,
			ArrivalEstimate estimate, OffsetDateTime plannedMotherArrivalAt, OffsetDateTime plannedFeederDepartureAt) {
		if (planned == null) {
			throw new IllegalArgumentException("A planned connection window calculation is required");
		}
		if (remaining != null) {
			return new AssessmentExplanation(remainingEvidence(remaining, estimate), remainingAssumptions(),
					ESTIMATED_ARRIVAL, null);
		}
		if (planned.status() == ConnectionWindowCalculation.Status.INCOMPLETE) {
			return new AssessmentExplanation(null, null, null, missing(plannedMotherArrivalAt, plannedFeederDepartureAt));
		}
		return new AssessmentExplanation(plannedEvidence(planned, estimate), plannedAssumptions(), LIMITED, null);
	}

	private static List<String> remainingEvidence(RemainingConnectionWindow remaining, ArrivalEstimate estimate) {
		String observed = remaining.status() == RemainingConnectionWindow.Status.AVAILABLE
				? remaining.duration()
				: "The connection is expected to be missed by " + remaining.missedBy() + ".";
		return List.of(REMAINING_WINDOW, observed, positionAndSpeed(estimate));
	}

	private static List<String> plannedEvidence(ConnectionWindowCalculation planned, ArrivalEstimate estimate) {
		String observed = planned.status() == ConnectionWindowCalculation.Status.AVAILABLE
				? planned.window().duration()
				: NOT_POSITIVE;
		return List.of(PLANNED_WINDOW, observed, reason(estimate));
	}

	private static List<String> remainingAssumptions() {
		return List.of(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);
	}

	private static List<String> plannedAssumptions() {
		return List.of(PLANNED_FALLBACK, HOUR_BANDS);
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
