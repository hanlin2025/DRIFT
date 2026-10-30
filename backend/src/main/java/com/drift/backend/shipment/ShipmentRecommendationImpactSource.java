package com.drift.backend.shipment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The impact a shipment read uses when it chooses a recommendation.
 * The assessment has not been returned, so the result is unavailable unless
 * {@code app.recommendation.impact-outcome} names a completed result.
 */
@Component
class ShipmentRecommendationImpactSource {

	private final ShipmentRecommendationImpact impact;

	ShipmentRecommendationImpactSource() {
		this(ShipmentRecommendationImpact.notReturned());
	}

	@Autowired
	ShipmentRecommendationImpactSource(
			@Value("${app.recommendation.impact-outcome:NOT_RETURNED}") String outcome) {
		this(impact(outcome));
	}

	private ShipmentRecommendationImpactSource(ShipmentRecommendationImpact impact) {
		this.impact = impact;
	}

	ShipmentRecommendationImpact current() {
		return impact;
	}

	private static ShipmentRecommendationImpact impact(String outcome) {
		return switch (outcome) {
			case "NOT_RETURNED" -> ShipmentRecommendationImpact.notReturned();
			case "NO_PREDICTED_IMPACT" -> ShipmentRecommendationImpact.noPredictedImpact();
			case "AFFECTS_CONSTRAINTS" -> ShipmentRecommendationImpact.affectsConstraints();
			default -> throw new IllegalStateException(
					"The recommendation impact outcome must be NOT_RETURNED, NO_PREDICTED_IMPACT, or AFFECTS_CONSTRAINTS.");
		};
	}
}
