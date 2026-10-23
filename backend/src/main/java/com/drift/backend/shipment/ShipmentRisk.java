package com.drift.backend.shipment;

import java.time.Duration;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Transshipment risk derived from the remaining connection window when that window is present, and otherwise from the planned connection window.")
public record ShipmentRisk(
		@Schema(example = "MEDIUM")
		Level level,
		@Schema(example = "Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.")
		String explanation) {

	public enum Level {
		LOW,
		MEDIUM,
		HIGH,
		CRITICAL
	}

	private static final long HIGH_BELOW_SECONDS = Duration.ofHours(12).toSeconds();
	private static final long MEDIUM_BELOW_SECONDS = Duration.ofHours(24).toSeconds();
	private static final String PLANNED_WINDOW = " The planned connection window is being used.";

	static ShipmentRisk of(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining) {
		if (planned == null) {
			throw new IllegalArgumentException("A planned connection window calculation is required");
		}
		if (remaining != null) {
			return fromRemaining(remaining);
		}
		return switch (planned.status()) {
			case INCOMPLETE -> null;
			case INVALID -> new ShipmentRisk(Level.CRITICAL,
					"The feeder vessel is planned to depart at or before the mother vessel's arrival, leaving no time to transfer cargo."
							+ PLANNED_WINDOW);
			case AVAILABLE -> plannedLevel(planned.window().totalSeconds());
		};
	}

	private static ShipmentRisk fromRemaining(RemainingConnectionWindow remaining) {
		return switch (remaining.status()) {
			case MISSED -> missed(remaining);
			case AVAILABLE -> remainingLevel(remaining.totalSeconds());
		};
	}

	private static ShipmentRisk missed(RemainingConnectionWindow remaining) {
		if (remaining.missedBySeconds() == 0L && "0 seconds".equals(remaining.missedBy())) {
			return new ShipmentRisk(Level.CRITICAL,
					"The connection is expected to be missed. The mother vessel's estimated arrival is at the planned feeder departure.");
		}
		return new ShipmentRisk(Level.CRITICAL,
				"The connection is expected to be missed by " + remaining.missedBy() + ".");
	}

	private static ShipmentRisk remainingLevel(long totalSeconds) {
		if (totalSeconds < HIGH_BELOW_SECONDS) {
			return new ShipmentRisk(Level.HIGH,
					"Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
		}
		if (totalSeconds < MEDIUM_BELOW_SECONDS) {
			return new ShipmentRisk(Level.MEDIUM,
					"Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
		}
		return new ShipmentRisk(Level.LOW,
				"At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
	}

	private static ShipmentRisk plannedLevel(long totalSeconds) {
		if (totalSeconds < HIGH_BELOW_SECONDS) {
			return new ShipmentRisk(Level.HIGH,
					"Less than 12 hours to transfer cargo between vessels." + PLANNED_WINDOW);
		}
		if (totalSeconds < MEDIUM_BELOW_SECONDS) {
			return new ShipmentRisk(Level.MEDIUM,
					"Less than 24 hours to transfer cargo between vessels." + PLANNED_WINDOW);
		}
		return new ShipmentRisk(Level.LOW,
				"At least 24 hours to transfer cargo between vessels." + PLANNED_WINDOW);
	}
}
