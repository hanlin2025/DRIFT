package com.drift.backend.shipment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Impact for the shipment read and for a decision on that same read.
 * The assessment path is authoritative. No assessment is stored, so the impact
 * stays unavailable. app.recommendation.impact-outcome is used only while the
 * recommendation-impact-override profile is active.
 */
@Component
class ShipmentRecommendationImpactSource {

	static final String OVERRIDE_PROFILE = "recommendation-impact-override";

	private final boolean overrideEnabled;
	private final ShipmentRecommendationImpact override;

	@Autowired
	ShipmentRecommendationImpactSource(Environment environment) {
		if (!environment.matchesProfiles(OVERRIDE_PROFILE)) {
			this.overrideEnabled = false;
			this.override = null;
			return;
		}
		this.overrideEnabled = true;
		this.override = configured(environment.getProperty("app.recommendation.impact-outcome", "NOT_RETURNED"));
	}

	ShipmentRecommendationImpactSource() {
		this.overrideEnabled = false;
		this.override = null;
	}

	ShipmentRecommendationImpact current() {
		if (!overrideEnabled) {
			return assessment();
		}
		return override;
	}

	private static ShipmentRecommendationImpact assessment() {
		return ShipmentRecommendationImpact.notReturned();
	}

	private static ShipmentRecommendationImpact configured(String configuredOutcome) {
		String outcome = configuredOutcome == null || configuredOutcome.isBlank() ? "NOT_RETURNED" : configuredOutcome;
		return switch (outcome) {
			case "NOT_RETURNED" -> ShipmentRecommendationImpact.notReturned();
			case "NO_PREDICTED_IMPACT" -> ShipmentRecommendationImpact.noPredictedImpact();
			case "AFFECTS_CONSTRAINTS" -> ShipmentRecommendationImpact.affectsConstraints();
			default -> throw new IllegalStateException("Unknown recommendation impact outcome");
		};
	}
}
