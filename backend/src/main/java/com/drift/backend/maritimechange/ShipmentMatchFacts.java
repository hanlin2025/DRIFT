package com.drift.backend.maritimechange;

import java.time.OffsetDateTime;

record ShipmentMatchFacts(
		long shipmentId,
		long owningCompanyId,
		String transshipmentPort,
		String motherVesselImo,
		String feederVesselImo,
		OffsetDateTime plannedMotherArrivalAt,
		OffsetDateTime plannedFeederDepartureAt) {
}
