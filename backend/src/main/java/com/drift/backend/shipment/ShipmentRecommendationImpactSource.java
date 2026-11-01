package com.drift.backend.shipment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The impact the current shipment read uses when it chooses a recommendation.
 * Until a stored assessment is returned, that impact is not available, so a
 * decision against this read is rejected and stores nothing.
 * The recommendation decision browser run can select a completed outcome
 * through app.recommendation.impact-outcome. The default stays not returned.
 */
@Component
class ShipmentRecommendationImpactSource {

	private final String configuredOutcome;

	ShipmentRecommendationImpactSource(
			@Value("${app.recommendation.impact-outcome:NOT_RETURNED}") String configuredOutcome) {
		this.configuredOutcome = configuredOutcome == null || configuredOutcome.isBlank()
				? "NOT_RETURNED" : configuredOutcome;
	}

	ShipmentRecommendationImpact current() {
		return switch (configuredOutcome) {
			case "NOT_RETURNED" -> ShipmentRecommendationImpact.notReturned();
			case "NO_PREDICTED_IMPACT" -> ShipmentRecommendationImpact.noPredictedImpact();
			case "AFFECTS_CONSTRAINTS" -> ShipmentRecommendationImpact.affectsConstraints();
			default -> throw new IllegalStateException("Unknown recommendation impact outcome");
		};
	}
}
