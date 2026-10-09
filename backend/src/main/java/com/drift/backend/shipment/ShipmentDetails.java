package com.drift.backend.shipment;

import java.time.OffsetDateTime;

record ShipmentDetails(
		String shipmentReference,
		String origin,
		String destination,
		String transshipmentPort,
		String motherVessel,
		OffsetDateTime plannedMotherArrivalAt,
		String feederVessel,
		OffsetDateTime plannedFeederDepartureAt) {

	static ShipmentDetails from(ShipmentDetailsRequest request) {
		return new ShipmentDetails(request.shipmentReference().strip(), request.origin().strip(),
				request.destination().strip(), request.transshipmentPort().strip(), request.motherVessel().strip(),
				request.plannedMotherArrivalAt(), request.feederVessel().strip(), request.plannedFeederDepartureAt());
	}
}

interface ShipmentDetailsRequest {
	String shipmentReference();
	String origin();
	String destination();
	String transshipmentPort();
	String motherVessel();
	OffsetDateTime plannedMotherArrivalAt();
	String feederVessel();
	OffsetDateTime plannedFeederDepartureAt();
}
