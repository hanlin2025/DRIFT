package com.drift.backend.shipment;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The inputs that produced {@link ShipmentRisk} on one read. The hour bands stay in the existing
 * explanation. Nothing here is stored, and a missing planned time is not an unavailable estimate.
 */
@Schema(description = "Inputs that produced the connection risk on this read. Calculated with that risk and not stored. A missing planned time is not assessed, and is not an unavailable arrival estimate.")
public record ShipmentRiskContributions(
		@Schema(description = "False when the planned mother-vessel arrival or the planned feeder-vessel departure is missing.")
		boolean assessed,
		@Schema(description = "Set only when the connection risk is not assessed.", nullable = true,
				example = "Connection risk is not assessed because the planned feeder-vessel departure is missing.")
		String notAssessed,
		@Schema(description = "The window in use and the measurement that produced the level. Empty when the risk is not assessed.")
		List<ShipmentRiskContribution> contributions) {

	static final String REMAINING_WINDOW = "Remaining connection window";
	static final String PLANNED_WINDOW = "Planned connection window";
	static final String NOT_POSITIVE = "The planned connection window is not positive.";
	static final String OUTDATED = "The mother vessel's position is more than six hours old.";
	static final String MISSING_MOTHER = "Connection risk is not assessed because the planned mother-vessel arrival is missing.";
	static final String MISSING_FEEDER = "Connection risk is not assessed because the planned feeder-vessel departure is missing.";
	static final String MISSING_BOTH = "Connection risk is not assessed because the planned mother-vessel arrival and the planned feeder-vessel departure are missing.";

	public ShipmentRiskContributions {
		contributions = contributions == null ? List.of() : List.copyOf(contributions);
		if (assessed) {
			if (notAssessed != null || contributions.isEmpty()) {
				throw new IllegalArgumentException("An assessed risk names its contributions and has no not-assessed sentence");
			}
		} else if (notAssessed == null || notAssessed.isBlank() || !contributions.isEmpty()) {
			throw new IllegalArgumentException("A risk that is not assessed has one sentence and no contributions");
		}
		EnumSet<ShipmentRiskContribution.Code> seen = EnumSet.noneOf(ShipmentRiskContribution.Code.class);
		for (ShipmentRiskContribution contribution : contributions) {
			if (!seen.add(contribution.code())) {
				throw new IllegalArgumentException("A risk contribution is repeated");
			}
		}
		if (assessed) {
			if (!seen.contains(ShipmentRiskContribution.Code.WINDOW_IN_USE)) {
				throw new IllegalArgumentException("An assessed risk names the window in use");
			}
			int measured = 0;
			if (seen.contains(ShipmentRiskContribution.Code.REMAINING_DURATION)) {
				measured++;
			}
			if (seen.contains(ShipmentRiskContribution.Code.MISSED_BY)) {
				measured++;
			}
			if (seen.contains(ShipmentRiskContribution.Code.PLANNED_DURATION)) {
				measured++;
			}
			if (seen.contains(ShipmentRiskContribution.Code.PLANNED_WINDOW)) {
				measured++;
			}
			if (measured != 1) {
				throw new IllegalArgumentException("An assessed risk has one measured window");
			}
			boolean planned = seen.contains(ShipmentRiskContribution.Code.PLANNED_DURATION)
					|| seen.contains(ShipmentRiskContribution.Code.PLANNED_WINDOW);
			if (seen.contains(ShipmentRiskContribution.Code.ESTIMATE_NOT_USED) != planned) {
				throw new IllegalArgumentException("The unused-estimate reason belongs only to the planned window");
			}
		}
	}

	static ShipmentRiskContributions of(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining,
			ArrivalEstimate arrival, OffsetDateTime plannedMotherArrivalAt, OffsetDateTime plannedFeederDepartureAt) {
		if (planned == null) {
			throw new IllegalArgumentException("A planned connection window calculation is required");
		}
		if (ShipmentRisk.of(planned, remaining) == null) {
			return notAssessed(plannedMotherArrivalAt, plannedFeederDepartureAt);
		}
		if (remaining != null) {
			return fromRemaining(remaining);
		}
		return fromPlanned(planned, arrival);
	}

	private static ShipmentRiskContributions notAssessed(OffsetDateTime plannedMotherArrivalAt,
			OffsetDateTime plannedFeederDepartureAt) {
		String sentence;
		if (plannedMotherArrivalAt == null && plannedFeederDepartureAt == null) {
			sentence = MISSING_BOTH;
		} else if (plannedMotherArrivalAt == null) {
			sentence = MISSING_MOTHER;
		} else if (plannedFeederDepartureAt == null) {
			sentence = MISSING_FEEDER;
		} else {
			throw new IllegalArgumentException("An unassessed risk needs a missing planned time");
		}
		return new ShipmentRiskContributions(false, sentence, List.of());
	}

	private static ShipmentRiskContributions fromRemaining(RemainingConnectionWindow remaining) {
		List<ShipmentRiskContribution> contributions = new ArrayList<>();
		contributions.add(ShipmentRiskContribution.window(REMAINING_WINDOW));
		if (remaining.status() == RemainingConnectionWindow.Status.AVAILABLE) {
			contributions.add(ShipmentRiskContribution.of(ShipmentRiskContribution.Code.REMAINING_DURATION,
					remaining.duration()));
		} else {
			contributions.add(ShipmentRiskContribution.of(ShipmentRiskContribution.Code.MISSED_BY, remaining.missedBy()));
		}
		return new ShipmentRiskContributions(true, null, contributions);
	}

	private static ShipmentRiskContributions fromPlanned(ConnectionWindowCalculation planned, ArrivalEstimate arrival) {
		List<ShipmentRiskContribution> contributions = new ArrayList<>();
		contributions.add(ShipmentRiskContribution.window(PLANNED_WINDOW));
		if (planned.status() == ConnectionWindowCalculation.Status.AVAILABLE) {
			contributions.add(ShipmentRiskContribution.of(ShipmentRiskContribution.Code.PLANNED_DURATION,
					planned.window().duration()));
		} else if (planned.status() == ConnectionWindowCalculation.Status.INVALID) {
			contributions.add(ShipmentRiskContribution.of(ShipmentRiskContribution.Code.PLANNED_WINDOW, NOT_POSITIVE));
		} else {
			throw new IllegalArgumentException("A planned-window risk is available or not positive");
		}
		contributions.add(ShipmentRiskContribution.of(ShipmentRiskContribution.Code.ESTIMATE_NOT_USED,
				estimateNotUsed(arrival)));
		return new ShipmentRiskContributions(true, null, contributions);
	}

	private static String estimateNotUsed(ArrivalEstimate arrival) {
		if (arrival != null && arrival.unavailableReason() != null) {
			return arrival.unavailableReason().message();
		}
		if (arrival != null && Boolean.TRUE.equals(arrival.possiblyOutdated())) {
			return OUTDATED;
		}
		throw new IllegalArgumentException("A planned-window risk needs the reason the estimate was not used");
	}
}
