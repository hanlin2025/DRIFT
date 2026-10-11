package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.drift.backend.company.Company;
import com.drift.backend.organisation.OrganisationResponse;

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
		@Schema(description = "Optimistic-lock version. Supply this value when updating the shipment.", example = "0")
		Long version,
		@Schema(description = "Planned time available between mother-vessel arrival and feeder-vessel departure. Calculated from the stored schedule whenever the shipment is read. Null when that window is missing or not positive.")
		ConnectionWindow connectionWindow,
		@Schema(description = "Latest retained AIS position whose vessel name matches the mother vessel. Null when the livestream has not retained one.")
		VesselPosition motherVesselPosition,
		@Schema(description = "Latest retained AIS position whose vessel name matches the feeder vessel. Null when the livestream has not retained one.")
		VesselPosition feederVesselPosition,
		@Schema(description = "Importer organisation linked to this shipment. Null when the shipment is not linked.", nullable = true)
		OrganisationResponse importer) {

	static ShipmentDetailResponse from(Shipment shipment, ConnectionWindow connectionWindow,
			VesselPosition motherVesselPosition, VesselPosition feederVesselPosition) {
		return new ShipmentDetailResponse(shipment.getId(), shipment.getShipmentReference(), shipment.getOrigin(),
				shipment.getDestination(), shipment.getTransshipmentPort(), shipment.getMotherVessel(),
				shipment.getPlannedMotherArrivalAt(),
				shipment.getFeederVessel(), shipment.getPlannedFeederDepartureAt(), shipment.getCreatedAt(),
				shipment.getVersion(), connectionWindow, motherVesselPosition, feederVesselPosition,
				organisation(shipment.getImporterCompany()));
	}

	private static OrganisationResponse organisation(Company company) {
		if (company == null) {
			return null;
		}
		return new OrganisationResponse(company.getId(), company.getCode(), company.getName());
	}
}
