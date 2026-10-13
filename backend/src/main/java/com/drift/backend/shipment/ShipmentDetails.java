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
		OffsetDateTime plannedFeederDepartureAt,
		String motherVesselImo,
		String motherVesselMmsi,
		String motherVesselOperator,
		String feederVesselImo,
		String feederVesselMmsi,
		String feederVesselOperator) {

	static ShipmentDetails from(ShipmentDetailsRequest request) {
		return new ShipmentDetails(request.shipmentReference().strip(), request.origin().strip(),
				request.destination().strip(), request.transshipmentPort().strip(), request.motherVessel().strip(),
				request.plannedMotherArrivalAt(), request.feederVessel().strip(), request.plannedFeederDepartureAt(),
				optional(request.motherVesselImo()), optional(request.motherVesselMmsi()),
				optional(request.motherVesselOperator()), optional(request.feederVesselImo()),
				optional(request.feederVesselMmsi()), optional(request.feederVesselOperator()));
	}

	private static String optional(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
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
	String motherVesselImo();
	String motherVesselMmsi();
	String motherVesselOperator();
	String feederVesselImo();
	String feederVesselMmsi();
	String feederVesselOperator();
}
