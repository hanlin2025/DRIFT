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
}
