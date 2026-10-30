package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.drift.backend.company.Company;
import com.drift.backend.organisation.OrganisationResponse;
import com.drift.backend.ais.feed.AisFeedStatus;
import com.drift.backend.ais.operation.OperationalState;

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
		@Schema(description = "Time from the mother vessel's estimated arrival to the planned feeder departure. Present when that estimate has an arrival time and is not possibly outdated, including an estimate that is exactly 6 hours old. Null when the estimate is missing, unavailable, or possibly outdated, or when the feeder departure is missing. Not stored.", nullable = true)
		RemainingConnectionWindow remainingConnectionWindow,
		@Schema(description = "Transshipment risk from the remaining connection window when that window is present, and otherwise from the planned connection window. CRITICAL when the window in use is missed or not positive, HIGH under 12 hours, MEDIUM under 24 hours, and LOW at 24 hours or more. Exactly 12 hours is MEDIUM and exactly 24 hours is LOW. The explanation names the window. Null when the planned mother-vessel arrival or the planned feeder-vessel departure is missing.", nullable = true)
		ShipmentRisk risk,
		@Schema(description = "Evidence, assumptions, and confidence for the risk on this same read. Calculated when the shipment is read and not stored. Distinct from the contribution list. An unavailable assessment names the missing planned time and does not return a limited confidence.")
		AssessmentExplanation assessmentExplanation,
		@Schema(description = "Inputs that produced the connection risk on this read. Taken from the same planned window and remaining window, and not stored.")
		ShipmentRiskContributions riskContributions,
		@Schema(description = "Latest retained AIS position whose vessel name matches the mother vessel. Null when the livestream has not retained one.")
		VesselPosition motherVesselPosition,
		@Schema(description = "Set when that AIS position names a different vessel from the stored mother vessel. Null when there is no position, the position has no name, or the names match after trimming and case folding.", nullable = true)
		String motherVesselNameWarning,
		@Schema(description = "Latest retained AIS position whose vessel name matches the feeder vessel. Null when the livestream has not retained one.")
		VesselPosition feederVesselPosition,
		@Schema(description = "Set when that AIS position names a different vessel from the stored feeder vessel. Null when there is no position, the position has no name, or the names match after trimming and case folding.", nullable = true)
		String feederVesselNameWarning,
		@Schema(description = "Operational state derived from the latest retained observation for the mother vessel. ingestedAt is when DRIFT stored that report. Null ingestedAt means no observation was retained.")
		OperationalState motherOperationalState,
		@Schema(description = "Operational state derived from the latest retained observation for the feeder vessel. ingestedAt is when DRIFT stored that report. Null ingestedAt means no observation was retained.")
		OperationalState feederOperationalState,
		@Schema(description = "Whether DRIFT is still receiving AIS reports, and when it stored the newest AIS_STREAM report. The result is the feed status and that report time only. Null when this response was assembled without reading the feed.", nullable = true)
		AisFeedStatus aisFeed,
		@Schema(description = "Comparison of the planned mother-vessel arrival with an estimate from that same retained mother-vessel position. Calculated when the shipment is read and not stored. An unavailable estimate contains only unavailableReason; every other comparison field is null. lastReceivedAt is the report time of that estimate. Null when the planned mother-vessel arrival is missing; that is not an unavailable reason.", nullable = true)
		ArrivalEstimate arrivalComparison,
		@Schema(description = "Importer organisation linked to this shipment. Null when the shipment is not linked.", nullable = true)
		OrganisationResponse importer,
		@Schema(description = "Lifecycle status stored for this shipment.", example = "ACTIVE")
		ShipmentStatus status,
		@Schema(description = "The recommendation for this read, from the connection risk and the impact result. Unavailable when either input is missing. It is not stored.",
				implementation = ShipmentRecommendation.class)
		ShipmentRecommendation recommendation,
		@Schema(description = "The current recommendation decision, when one has been recorded. Null when no decision is stored. Opening the shipment again returns the outcome, the recommendation it was made against, the actor, and the time.",
				types = { "object", "null" }, implementation = RecommendationDecisionResponse.class)
		RecommendationDecisionResponse recommendationDecision) {

	static ShipmentDetailResponse from(Shipment shipment, ConnectionWindowCalculation calculation,
			RemainingConnectionWindow remainingConnectionWindow, VesselPosition motherVesselPosition,
			VesselPosition feederVesselPosition, ArrivalEstimate arrivalComparison) {
		return from(shipment, calculation, remainingConnectionWindow, motherVesselPosition, feederVesselPosition,
				arrivalComparison, null, ShipmentRecommendationImpact.notReturned());
	}

	static ShipmentDetailResponse from(Shipment shipment, ConnectionWindowCalculation calculation,
			RemainingConnectionWindow remainingConnectionWindow, VesselPosition motherVesselPosition,
			VesselPosition feederVesselPosition, ArrivalEstimate arrivalComparison,
			RecommendationDecisionResponse recommendationDecision, ShipmentRecommendationImpact impact) {
		ShipmentRisk risk = ShipmentRisk.of(calculation, remainingConnectionWindow);
		return new ShipmentDetailResponse(shipment.getId(), shipment.getCompany().getId(), shipment.getShipmentReference(), shipment.getOrigin(),
				shipment.getDestination(), shipment.getTransshipmentPort(), shipment.getMotherVessel(),
				shipment.getPlannedMotherArrivalAt(), shipment.getMotherVesselImo(), shipment.getMotherVesselMmsi(),
				shipment.getMotherVesselOperator(), shipment.getFeederVessel(), shipment.getPlannedFeederDepartureAt(),
				shipment.getFeederVesselImo(), shipment.getFeederVesselMmsi(), shipment.getFeederVesselOperator(),
				shipment.getCreatedAt(), shipment.getVersion(), calculation.window(), remainingConnectionWindow,
				risk,
				AssessmentExplanation.of(calculation, remainingConnectionWindow, arrivalComparison,
						shipment.getPlannedMotherArrivalAt(), shipment.getPlannedFeederDepartureAt()),
				ShipmentRiskContributions.of(calculation, remainingConnectionWindow, arrivalComparison,
						shipment.getPlannedMotherArrivalAt(), shipment.getPlannedFeederDepartureAt()),
				motherVesselPosition, VesselNames.warning(shipment.getMotherVessel(), motherVesselPosition, VesselNames.MOTHER_MISMATCH),
				feederVesselPosition, VesselNames.warning(shipment.getFeederVessel(), feederVesselPosition, VesselNames.FEEDER_MISMATCH),
				ShipmentOperationalStates.forShipmentVessel(shipment.getMotherVessel(), shipment.getTransshipmentPort()),
				ShipmentOperationalStates.forShipmentVessel(shipment.getFeederVessel(), shipment.getTransshipmentPort()),
				ShipmentAisFeed.current(),
				arrivalComparison,
				organisation(shipment.getImporterCompany()), shipment.getStatus(),
				ShipmentRecommendation.of(risk, impact), recommendationDecision);
	}

	private static OrganisationResponse organisation(Company company) {
		if (company == null) {
			return null;
		}
		return new OrganisationResponse(company.getId(), company.getCode(), company.getName());
	}
}
