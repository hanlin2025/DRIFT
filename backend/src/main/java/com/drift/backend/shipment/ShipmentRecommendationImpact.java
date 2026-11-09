package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The impact result read with the connection risk, taken from the delay comparison on the same read.
 * A completed result has no detail. An unavailable result names why the comparison could not be made.
 */
@Schema(description = "Impact result read with the connection risk, taken from the delay comparison on the same read. AFFECTS_CONSTRAINTS is a predicted arrival after the importer's recorded deadline and NO_PREDICTED_IMPACT is one at or before it; both are completed results and have no detail. UNAVAILABLE carries the delay comparison's message as the reason. This does not calculate a second impact.")
public record ShipmentRecommendationImpact(
		@Schema(example = "UNAVAILABLE")
		Outcome outcome,
		@Schema(description = "Set only when the impact is unavailable.", nullable = true,
				example = "No mother-vessel transshipment-arrival deadline is recorded for the current importer.")
		String detail) {

	public enum Outcome {
		AFFECTS_CONSTRAINTS,
		NO_PREDICTED_IMPACT,
		UNAVAILABLE
	}

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

	/** Exceeding the importer's deadline affects the constraints; a comparison that cannot be made is unavailable. */
	static ShipmentRecommendationImpact from(DelayComparison comparison) {
		if (comparison == null) {
			throw new IllegalArgumentException("A delay comparison is required to map customer impact");
		}
		if (comparison.outcome() == null) {
			return unavailable(comparison.message());
		}
		return switch (comparison.outcome()) {
			case EXCEEDS_DEADLINE -> affectsConstraints();
			case NO_PREDICTED_IMPACT -> noPredictedImpact();
		};
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
