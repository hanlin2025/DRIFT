package com.drift.backend.shipment;

import java.time.Duration;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Transshipment risk derived from the planned connection window.")
public record ShipmentRisk(
		@Schema(example = "MEDIUM")
		Level level,
		@Schema(example = "Less than 24 hours to transfer cargo between vessels.")
		String explanation) {

	public enum Level {
		LOW,
		MEDIUM,
		HIGH,
		CRITICAL
	}

	private static final long HIGH_BELOW_SECONDS = Duration.ofHours(12).toSeconds();
	private static final long MEDIUM_BELOW_SECONDS = Duration.ofHours(24).toSeconds();

	static ShipmentRisk of(ConnectionWindowCalculation calculation) {
		return switch (calculation.status()) {
			case INCOMPLETE -> null;
			case INVALID -> new ShipmentRisk(Level.CRITICAL,
					"The feeder vessel is planned to depart before the mother vessel arrives.");
			case AVAILABLE -> available(calculation.window().totalSeconds());
		};
	}

	private static ShipmentRisk available(long totalSeconds) {
		if (totalSeconds < HIGH_BELOW_SECONDS) {
			return new ShipmentRisk(Level.HIGH, "Less than 12 hours to transfer cargo between vessels.");
		}
		if (totalSeconds < MEDIUM_BELOW_SECONDS) {
			return new ShipmentRisk(Level.MEDIUM, "Less than 24 hours to transfer cargo between vessels.");
		}
		return new ShipmentRisk(Level.LOW, "At least 24 hours to transfer cargo between vessels.");
	}
}
