package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShipmentDetailResponse(
		Long id,
		String shipmentReference,
		String origin,
		String destination,
		String transshipmentPort,
		String motherVessel,
		OffsetDateTime plannedMotherArrivalAt,
		String feederVessel,
		OffsetDateTime plannedFeederDepartureAt,
		Instant createdAt,
		@Schema(description = "Planned time available between mother-vessel arrival and feeder-vessel departure. Calculated from the stored schedule whenever the shipment is read. Null when that window is missing or not positive.")
		ConnectionWindow connectionWindow,
		@Schema(description = "Latest retained AIS position whose vessel name matches the mother vessel. Null when the livestream has not retained one.")
		VesselPosition motherVesselPosition,
		@Schema(description = "Latest retained AIS position whose vessel name matches the feeder vessel. Null when the livestream has not retained one.")
		VesselPosition feederVesselPosition,
		@Schema(description = "Importer organisation allowed to follow this shipment. Null when the shipment is not linked.",
				types = { "object", "null" }, implementation = ImporterOrganisation.class)
		ImporterOrganisation importerOrganisation) {

	static ShipmentDetailResponse from(Shipment shipment, ConnectionWindow connectionWindow,
			VesselPosition motherVesselPosition, VesselPosition feederVesselPosition) {
		return new ShipmentDetailResponse(shipment.getId(), shipment.getShipmentReference(), shipment.getOrigin(),
				shipment.getDestination(), shipment.getTransshipmentPort(), shipment.getMotherVessel(),
				shipment.getPlannedMotherArrivalAt(),
				shipment.getFeederVessel(), shipment.getPlannedFeederDepartureAt(), shipment.getCreatedAt(),
				connectionWindow, motherVesselPosition, feederVesselPosition,
				ImporterOrganisation.of(shipment.getImporterCompany()));
	}
}
