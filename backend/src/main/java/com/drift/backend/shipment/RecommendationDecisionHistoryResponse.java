package com.drift.backend.shipment;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record RecommendationDecisionHistoryResponse(
		@Schema(description = "Identity of this history row. Newer rows have higher ids. Equal decision times are ordered by this id descending.",
				example = "12")
		Long id,
		@Schema(description = "APPROVED accepts the recommendation shown at the time, MODIFIED stores a different response, and DISMISSED sets that recommendation aside.",
				example = "APPROVED")
		RecommendationDecisionOutcome outcome,
		@Schema(description = "Viewer label of the recommendation this decision was made against.", example = "No action")
		String recommendationName,
		@Schema(description = "Wording of the recommendation this decision was made against, as it read at the time.")
		String recommendationWording,
		@Schema(description = "Viewer label of the response chosen for a modification. Null for approval and dismissal.",
				types = { "string", "null" })
		String selectedResponseName,
		@Schema(description = "Wording of the response chosen for a modification. Null for approval and dismissal.",
				types = { "string", "null" })
		String selectedResponseWording,
		@Schema(description = "Id of the user who recorded this decision.", example = "4")
		Long decidedByUserId,
		@Schema(description = "Name of the user when this decision was recorded. A later profile change does not rewrite it.",
				example = "Ada Lee")
		String decidedByName,
		@Schema(description = "When this decision was recorded by the server.", example = "2026-10-28T06:00:00Z")
		Instant decidedAt) {

	static RecommendationDecisionHistoryResponse from(RecommendationDecisionHistory history) {
		return new RecommendationDecisionHistoryResponse(history.getId(), history.getOutcome(),
				history.getRecommendationName(), history.getRecommendationWording(), history.getSelectedResponseName(),
				history.getSelectedResponseWording(), history.getDecidedBy().getId(), history.getDecidedByName(),
				history.getDecidedAt());
	}
}
