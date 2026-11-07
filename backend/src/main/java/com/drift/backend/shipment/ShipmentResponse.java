package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.drift.backend.company.Company;
import com.drift.backend.organisation.OrganisationResponse;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShipmentResponse(
		Long id,
		@Schema(description = "Id of the company that owns this shipment. A shipment visible through an importer link still returns the owning company's id.", example = "1")
		Long companyId,
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
		@Schema(description = "Transshipment risk calculated exactly as on the shipment detail: from the remaining connection window when a usable AIS arrival estimate exists, otherwise from the planned window. CRITICAL when the window in use is missed or not positive, HIGH under 12 hours, MEDIUM under 24 hours, otherwise LOW. Null (not assessed) when the feeder departure is missing.", nullable = true)
		ShipmentRisk risk,
		@Schema(description = "Importer organisation linked to this shipment. Null when the shipment is not linked.", nullable = true)
		OrganisationResponse importer,
		@Schema(description = "Lifecycle status stored for this shipment.", example = "ACTIVE")
		ShipmentStatus status,
		@Schema(description = "When this shipment was last updated. Archiving records the archive time here.", example = "2026-10-18T02:15:00Z")
		Instant updatedAt,
		@Schema(description = "Full name of the user who last updated this shipment. On an archived shipment this is who archived it.", example = "Ada Lee")
		String updatedByName) {

	static ShipmentResponse from(Shipment shipment, ShipmentConnectionRisk.Snapshot connection) {
		return new ShipmentResponse(shipment.getId(), shipment.getCompany().getId(), shipment.getShipmentReference(), shipment.getOrigin(),
				shipment.getDestination(), shipment.getTransshipmentPort(), shipment.getMotherVessel(),
				shipment.getPlannedMotherArrivalAt(),
				shipment.getFeederVessel(), shipment.getPlannedFeederDepartureAt(), shipment.getCreatedAt(),
				shipment.getVersion(), connection.planned().window(), connection.risk(),
				importer(shipment.getImporterCompany()), shipment.getStatus(), shipment.getUpdatedAt(),
				shipment.getUpdatedBy().getFullName());
	}

	static OrganisationResponse importer(Company company) {
		if (company == null) {
			return null;
		}
		return new OrganisationResponse(company.getId(), company.getCode(), company.getName());
	}
}
