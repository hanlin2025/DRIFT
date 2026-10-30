package com.drift.backend.shipment;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record RecommendationDecisionResponse(
		@Schema(description = "APPROVED accepts the recommendation shown at the time, MODIFIED stores a different response, and DISMISSED sets that recommendation aside.",
				example = "APPROVED")
		RecommendationDecisionOutcome outcome,
		@Schema(description = "Viewer label of the recommendation this decision was made against.", example = "No action")
		String recommendationName,
		@Schema(description = "Wording of the recommendation this decision was made against, as it read at the time.",
				example = "No action is needed. The connection risk is low, and the prediction does not affect the importer's constraints.")
		String recommendationWording,
		@Schema(description = "Viewer label of the response chosen for a modification. Null for approval and dismissal.",
				example = "Monitor", types = { "string", "null" })
		String selectedResponseName,
		@Schema(description = "Wording of the response chosen for a modification. Null for approval and dismissal.",
				types = { "string", "null" })
		String selectedResponseWording,
		@Schema(description = "Id of the user who recorded this decision.", example = "4")
		Long decidedByUserId,
		@Schema(description = "Full name of the user who recorded this decision.", example = "Ada Lee")
		String decidedByName,
		@Schema(description = "When this decision was recorded.", example = "2026-10-28T06:00:00Z")
		Instant decidedAt) {

	static RecommendationDecisionResponse from(RecommendationDecision decision) {
		if (decision == null) {
			return null;
		}
		return new RecommendationDecisionResponse(decision.getOutcome(), decision.getRecommendationName(),
				decision.getRecommendationWording(), decision.getSelectedResponseName(),
				decision.getSelectedResponseWording(), decision.getDecidedBy().getId(),
				decision.getDecidedBy().getFullName(), decision.getDecidedAt());
	}
}
