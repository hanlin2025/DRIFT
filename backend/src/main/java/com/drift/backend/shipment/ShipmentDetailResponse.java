package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.drift.backend.company.Company;
import com.drift.backend.organisation.OrganisationResponse;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShipmentDetailResponse(
		Long id,
		@Schema(description = "Id of the company that owns this shipment. A shipment visible through an importer link still returns the owning company's id.", example = "1")
		Long companyId,
		String shipmentReference,
		String origin,
		String destination,
		String transshipmentPort,
		String motherVessel,
		OffsetDateTime plannedMotherArrivalAt,
		@Schema(description = "Stored 7-digit IMO of the mother vessel. Null when the shipment does not have one. This is shipment data, not a value copied from AIS.", example = "9387421", nullable = true)
		String motherVesselImo,
		@Schema(description = "Stored 9-digit MMSI of the mother vessel. Null when the shipment does not have one.", example = "563000111", nullable = true)
		String motherVesselMmsi,
		@Schema(description = "Stored operator of the mother vessel. Null when the shipment does not have one.", example = "Harbourline", nullable = true)
		String motherVesselOperator,
		String feederVessel,
		OffsetDateTime plannedFeederDepartureAt,
		@Schema(description = "Stored 7-digit IMO of the feeder vessel. Null when the shipment does not have one.", example = "9411882", nullable = true)
		String feederVesselImo,
		@Schema(description = "Stored 9-digit MMSI of the feeder vessel. Null when the shipment does not have one.", example = "565987654", nullable = true)
		String feederVesselMmsi,
		@Schema(description = "Stored operator of the feeder vessel. Null when the shipment does not have one.", example = "Strait Feeders", nullable = true)
		String feederVesselOperator,
		Instant createdAt,
		@Schema(description = "Optimistic-lock version. Supply this value when updating the shipment.", example = "0")
		Long version,
		@Schema(description = "Planned time available between mother-vessel arrival and feeder-vessel departure. Calculated from the stored schedule whenever the shipment is read. Null when that window is missing or not positive.")
		ConnectionWindow connectionWindow,
		@Schema(description = "Transshipment risk derived from the connection window: CRITICAL when the feeder departs at or before the mother vessel's arrival, HIGH under 12 hours, MEDIUM under 24 hours, otherwise LOW. Null when the feeder schedule is missing.", nullable = true)
		ShipmentRisk risk,
		@Schema(description = "Latest retained AIS position whose vessel name matches the mother vessel. Null when the livestream has not retained one.")
		VesselPosition motherVesselPosition,
		@Schema(description = "Set when that AIS position names a different vessel from the stored mother vessel. Null when there is no position, the position has no name, or the names match after trimming and case folding.", nullable = true)
		String motherVesselNameWarning,
		@Schema(description = "Latest retained AIS position whose vessel name matches the feeder vessel. Null when the livestream has not retained one.")
		VesselPosition feederVesselPosition,
		@Schema(description = "Set when that AIS position names a different vessel from the stored feeder vessel. Null when there is no position, the position has no name, or the names match after trimming and case folding.", nullable = true)
		String feederVesselNameWarning,
		@Schema(description = "Importer organisation linked to this shipment. Null when the shipment is not linked.", nullable = true)
		OrganisationResponse importer,
		@Schema(description = "Lifecycle status stored for this shipment.", example = "ACTIVE")
		ShipmentStatus status) {

	static ShipmentDetailResponse from(Shipment shipment, ConnectionWindowCalculation calculation,
			VesselPosition motherVesselPosition, VesselPosition feederVesselPosition) {
		return new ShipmentDetailResponse(shipment.getId(), shipment.getCompany().getId(), shipment.getShipmentReference(), shipment.getOrigin(),
				shipment.getDestination(), shipment.getTransshipmentPort(), shipment.getMotherVessel(),
				shipment.getPlannedMotherArrivalAt(), shipment.getMotherVesselImo(), shipment.getMotherVesselMmsi(),
				shipment.getMotherVesselOperator(), shipment.getFeederVessel(), shipment.getPlannedFeederDepartureAt(),
				shipment.getFeederVesselImo(), shipment.getFeederVesselMmsi(), shipment.getFeederVesselOperator(),
				shipment.getCreatedAt(), shipment.getVersion(), calculation.window(), ShipmentRisk.of(calculation),
				motherVesselPosition, VesselNames.warning(shipment.getMotherVessel(), motherVesselPosition, VesselNames.MOTHER_MISMATCH),
				feederVesselPosition, VesselNames.warning(shipment.getFeederVessel(), feederVesselPosition, VesselNames.FEEDER_MISMATCH),
				organisation(shipment.getImporterCompany()), shipment.getStatus());
	}

	private static OrganisationResponse organisation(Company company) {
		if (company == null) {
			return null;
		}
		return new OrganisationResponse(company.getId(), company.getCode(), company.getName());
	}
}
