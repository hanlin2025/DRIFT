package com.drift.backend.shipment;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

@Service
public class ConnectionWindowService {

	public ConnectionWindowCalculation calculate(OffsetDateTime plannedMotherArrivalAt,
			OffsetDateTime plannedFeederDepartureAt) {
		if (plannedMotherArrivalAt == null || plannedFeederDepartureAt == null) {
			return ConnectionWindowCalculation.incomplete();
		}
		Duration between = Duration.between(plannedMotherArrivalAt, plannedFeederDepartureAt);
		if (!between.isPositive()) {
			return ConnectionWindowCalculation.invalid();
		}
		return ConnectionWindowCalculation.available(ConnectionWindow.of(between));
	}

	public RemainingConnectionWindow remaining(OffsetDateTime plannedFeederDepartureAt, ArrivalEstimate estimate) {
		if (plannedFeederDepartureAt == null || !usable(estimate)) {
			return null;
		}
		return remaining(plannedFeederDepartureAt, estimate.estimatedArrivalAt());
	}

	/** Compares a source-backed expected arrival with the planned feeder departure. */
	RemainingConnectionWindow remaining(OffsetDateTime plannedFeederDepartureAt, OffsetDateTime expectedArrivalAt) {
		if (plannedFeederDepartureAt == null || expectedArrivalAt == null) return null;
		Duration between = Duration.between(expectedArrivalAt, plannedFeederDepartureAt);
		if (between.isPositive()) {
			ConnectionWindow window = ConnectionWindow.of(between);
			return RemainingConnectionWindow.available(window.duration(), window.totalSeconds());
		}
		Duration overrun = between.negated();
		return RemainingConnectionWindow.missed(ConnectionWindow.durationText(overrun), overrun.getSeconds());
	}

	private static boolean usable(ArrivalEstimate estimate) {
		return estimate != null
				&& estimate.estimatedArrivalAt() != null
				&& estimate.unavailableReason() == null
				&& Boolean.FALSE.equals(estimate.possiblyOutdated());
	}
}
