package com.drift.backend.shipment;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Chance that the mother vessel arrives after the planned feeder departure minus the handling threshold.
 * Calculated when the shipment is read and not stored. It is not a risk level and is not calibrated.
 * See docs/cdg42-miss-probability-decision.md.
 */
@Schema(description = "Chance that the mother vessel arrives after the planned feeder departure, from the arrival estimate on this read. Calculated when the shipment is read and not stored. This is a rough estimate from uncalibrated parameters, not a risk level, and not a confirmed missed transshipment: with a handling threshold of 0 hours it only means arriving after the planned feeder departure. An available chance has the probability, display, label, the arrival and departure used, and plannedWindowInvalid. An unavailable chance has only unavailableReason.")
public record LateArrivalChance(
		@Schema(description = "True when the chance was calculated.")
		boolean available,
		@Schema(description = "Raw probability in [0, 1]. Null when the chance is unavailable.", example = "0.3504", nullable = true)
		Double probability,
		@Schema(description = "Probability rounded half-up to the nearest 5%. A rounded 0 is \"<5%\" and a rounded 100 is \">95%\"; 0% and 100% are never shown. Null when the chance is unavailable.", example = "35%", nullable = true)
		String display,
		@Schema(description = "Full sentence with the rough-estimate marker. Null when the chance is unavailable.", example = "Chance of arriving after feeder departure: 35% · rough estimate", nullable = true)
		String label,
		@Schema(description = "Estimated mother-vessel arrival used. The same value as arrivalComparison.estimatedArrivalAt on this response. Null when the chance is unavailable.", nullable = true)
		OffsetDateTime arrivalUsed,
		@Schema(description = "Planned feeder-vessel departure used. Null when the chance is unavailable.", nullable = true)
		OffsetDateTime plannedFeederDepartureUsed,
		@Schema(description = "True when the planned feeder departure is at or before the planned mother-vessel arrival. The probability is still calculated as normal. Null when the chance is unavailable.", nullable = true)
		Boolean plannedWindowInvalid,
		@Schema(description = "Why the chance was not calculated. Only one reason is returned, checked in this order: MISSING_PLANNED_MOTHER_ARRIVAL, MISSING_FEEDER_DEPARTURE, FEEDER_DEPARTURE_PASSED, the arrival estimate's own reason (NO_POSITION, INVALID_COORDINATES, MISSING_PORT, UNKNOWN_PORT, UNUSABLE_SPEED, or any other estimate code unchanged), then POSITION_OUTDATED. Null when the chance is available.", nullable = true)
		ArrivalEstimate.UnavailableReason unavailableReason) {

	/** Handling time needed before feeder departure. Suggested, uncalibrated, see docs/cdg42-miss-probability-decision.md. */
	static final Duration HANDLING_THRESHOLD = Duration.ZERO;

	static final String MISSING_PLANNED_MOTHER_ARRIVAL = "MISSING_PLANNED_MOTHER_ARRIVAL";
	static final String MISSING_FEEDER_DEPARTURE = "MISSING_FEEDER_DEPARTURE";
	static final String FEEDER_DEPARTURE_PASSED = "FEEDER_DEPARTURE_PASSED";
	static final String POSITION_OUTDATED = "POSITION_OUTDATED";

	static final String LABEL_PREFIX = "Chance of arriving after feeder departure: ";
	static final String ROUGH_ESTIMATE = " · rough estimate";
	static final String UNAVAILABLE_PREFIX = "Chance of arriving after feeder departure unavailable: ";

	public LateArrivalChance {
		if (available) {
			if (probability == null || !(probability >= 0 && probability <= 1) || unavailableReason != null
					|| arrivalUsed == null || plannedFeederDepartureUsed == null || plannedWindowInvalid == null) {
				throw new IllegalArgumentException("An available chance has a probability, its inputs, and no reason");
			}
			if (!display(probability).equals(display) || !(LABEL_PREFIX + display + ROUGH_ESTIMATE).equals(label)) {
				throw new IllegalArgumentException("The display and label must match the probability");
			}
		} else if (unavailableReason == null || probability != null || display != null || label != null
				|| arrivalUsed != null || plannedFeederDepartureUsed != null || plannedWindowInvalid != null) {
			throw new IllegalArgumentException("An unavailable chance only carries its reason");
		} else if (!unavailableReason.message().startsWith(UNAVAILABLE_PREFIX)) {
			throw new IllegalArgumentException("An unavailable reason names the chance it belongs to");
		}
	}

	/**
	 * Returns the first unavailable reason in the recorded order, or otherwise the chance from
	 * {@link StraightLineNormalArrival}. {@code now} must be the instant the estimate's freshness was judged at.
	 */
	static LateArrivalChance of(ArrivalEstimate estimate, OffsetDateTime plannedMotherArrivalAt,
			OffsetDateTime plannedFeederDepartureAt, ConnectionWindowCalculation planned, Instant now) {
		if (planned == null || now == null) {
			throw new IllegalArgumentException("A planned window calculation and the evaluation time are required");
		}
		if (plannedMotherArrivalAt == null) {
			return unavailable(MISSING_PLANNED_MOTHER_ARRIVAL,
					"the planned mother-vessel arrival is not set, so no arrival estimate exists.");
		}
		if (estimate == null) {
			throw new IllegalArgumentException("A planned mother-vessel arrival always has an arrival estimate");
		}
		if (plannedFeederDepartureAt == null) {
			return unavailable(MISSING_FEEDER_DEPARTURE, "the planned feeder departure is not set.");
		}
		if (now.isAfter(plannedFeederDepartureAt.toInstant())) {
			return unavailable(FEEDER_DEPARTURE_PASSED,
					"the planned feeder departure has already passed, and DRIFT cannot confirm whether the connection was made.");
		}
		if (estimate.unavailableReason() != null) {
			String code = estimate.unavailableReason().code();
			return unavailable(code, estimateSuffix(code));
		}
		if (Boolean.TRUE.equals(estimate.possiblyOutdated())) {
			return unavailable(POSITION_OUTDATED, "the latest vessel position is more than 6 hours old.");
		}
		ArrivalDistribution arrival = new StraightLineNormalArrival(estimate, now);
		double probability = arrival.probabilityArrivingAfter(
				plannedFeederDepartureAt.toInstant().minus(HANDLING_THRESHOLD));
		String display = display(probability);
		return new LateArrivalChance(true, probability, display, LABEL_PREFIX + display + ROUGH_ESTIMATE,
				estimate.estimatedArrivalAt(), plannedFeederDepartureAt,
				planned.status() == ConnectionWindowCalculation.Status.INVALID, null);
	}

	/** Rounds half-up to the nearest 5% and never shows 0% or 100%. */
	static String display(double probability) {
		long rounded = (long) Math.floor(probability * 20 + 0.5) * 5;
		if (rounded <= 0) {
			return "<5%";
		}
		if (rounded >= 100) {
			return ">95%";
		}
		return rounded + "%";
	}

	private static String estimateSuffix(String code) {
		return switch (code) {
			case ArrivalEstimateCalculator.NO_POSITION -> "no position has been received for the mother vessel.";
			case ArrivalEstimateCalculator.INVALID_COORDINATES -> "the vessel or port position cannot be used.";
			case ArrivalEstimateCalculator.MISSING_PORT -> "the transshipment port is not set for this shipment.";
			case ArrivalEstimateCalculator.UNKNOWN_PORT -> "the transshipment port is not on the route map.";
			case ArrivalEstimateCalculator.UNUSABLE_SPEED ->
				"the vessel's speed is missing or below 0.5 knots, so arrival cannot be estimated.";
			default -> "the arrival estimate cannot be used.";
		};
	}

	private static LateArrivalChance unavailable(String code, String suffix) {
		return new LateArrivalChance(false, null, null, null, null, null, null,
				new ArrivalEstimate.UnavailableReason(code, UNAVAILABLE_PREFIX + suffix));
	}
}
