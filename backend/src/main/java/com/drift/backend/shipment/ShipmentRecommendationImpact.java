package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The impact result read with the connection risk. This does not assess a delay.
 * A completed result has no detail. An unavailable result names why, including an assessment
 * that has not been returned.
 */
@Schema(description = "Impact result read with the connection risk. AFFECTS_CONSTRAINTS and NO_PREDICTED_IMPACT are completed results and have no detail. UNAVAILABLE names the reason, including an assessment that has not been returned. This does not calculate a second impact.")
public record ShipmentRecommendationImpact(
		@Schema(example = "UNAVAILABLE")
		Outcome outcome,
		@Schema(description = "Set only when the impact is unavailable.", nullable = true,
				example = "The impact assessment has not been returned.")
		String detail) {

	public enum Outcome {
		AFFECTS_CONSTRAINTS,
		NO_PREDICTED_IMPACT,
		UNAVAILABLE
	}

	static final String NOT_RETURNED = "The impact assessment has not been returned.";

	public ShipmentRecommendationImpact {
		if (outcome == null) {
			throw new IllegalArgumentException("An impact outcome is required");
		}
		if (outcome == Outcome.UNAVAILABLE) {
			if (detail == null || detail.isBlank()) {
				throw new IllegalArgumentException("An unavailable impact names the reason");
			}
		} else if (detail != null) {
			throw new IllegalArgumentException("A completed impact has no unavailable reason");
		}
	}

	static ShipmentRecommendationImpact notReturned() {
		return unavailable(NOT_RETURNED);
	}

	static ShipmentRecommendationImpact affectsConstraints() {
		return new ShipmentRecommendationImpact(Outcome.AFFECTS_CONSTRAINTS, null);
	}

	static ShipmentRecommendationImpact noPredictedImpact() {
		return new ShipmentRecommendationImpact(Outcome.NO_PREDICTED_IMPACT, null);
	}

	static ShipmentRecommendationImpact unavailable(String detail) {
		return new ShipmentRecommendationImpact(Outcome.UNAVAILABLE, detail);
	}
}
