package com.drift.backend.shipment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Impact for the shipment read and for a decision on that same read.
 * The assessment path is authoritative. No assessment is stored, so the impact
 * stays unavailable. A completed outcome is used only when the recommendation
 * decision runner sets DRIFT_RECOMMENDATION_IMPACT_OVERRIDE to its own token.
 * That switch is not application configuration.
 */
@Component
class ShipmentRecommendationImpactSource {

	static final String RUNNER_SWITCH = "DRIFT_RECOMMENDATION_IMPACT_OVERRIDE";
	static final String RUNNER_SWITCH_VALUE = "cdg275-e2e";
	static final String OUTCOME_ENV = "DRIFT_RECOMMENDATION_IMPACT_OUTCOME";

	private final boolean overrideEnabled;
	private final ShipmentRecommendationImpact override;

	@Autowired
	ShipmentRecommendationImpactSource(Environment environment) {
		if (!RUNNER_SWITCH_VALUE.equals(environment.getProperty(RUNNER_SWITCH))) {
			this.overrideEnabled = false;
			this.override = null;
			return;
		}
		this.overrideEnabled = true;
		this.override = configured(environment.getProperty(OUTCOME_ENV, "NOT_RETURNED"));
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
