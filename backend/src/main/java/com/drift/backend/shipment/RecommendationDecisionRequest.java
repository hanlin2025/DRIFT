package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record RecommendationDecisionRequest(
		@NotNull
		@Schema(description = "APPROVED accepts the recommendation, MODIFIED stores a different response from the recommendation set, and DISMISSED sets the recommendation aside.",
				example = "APPROVED")
		RecommendationDecisionOutcome outcome,
		@Schema(description = "Code of the response chosen when the outcome is MODIFIED. Omitted for approval and dismissal.",
				example = "MONITOR_CONSTRAINTS")
		ShipmentRecommendedResponse.Code selectedResponse) {
}
