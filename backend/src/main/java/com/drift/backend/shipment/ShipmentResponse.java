package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;

public record ShipmentResponse(
		Long id,
		String shipmentReference,
		String origin,
		String destination,
		String motherVessel,
		OffsetDateTime plannedMotherArrivalAt,
		String feederVessel,
		OffsetDateTime plannedFeederDepartureAt,
		Instant createdAt) {

	static ShipmentResponse from(Shipment shipment) {
		return new ShipmentResponse(shipment.getId(), shipment.getShipmentReference(), shipment.getOrigin(),
				shipment.getDestination(), shipment.getMotherVessel(), shipment.getPlannedMotherArrivalAt(),
				shipment.getFeederVessel(), shipment.getPlannedFeederDepartureAt(), shipment.getCreatedAt());
	}
}
