package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One recommended response from the connection risk on this read and the impact result.
 * Calculated when the shipment is read and not stored. A missing risk, an unavailable impact,
 * and a completed recommendation that no action is needed stay distinct.
 */
@Schema(description = "One recommended response from the connection risk on this read and the impact result. Calculated when the shipment is read and not stored. Low risk with no predicted impact is NO_ACTION. Low risk that affects the constraints is MONITOR_CONSTRAINTS. Medium is NOTE_TIMING or REVIEW_PLAN. High is WATCH_CONNECTION or PREPARE_INTERVENTION. Critical, including a missed remaining window and a planned window that is not positive, is CONFIRM_CONNECTION or ACT_ON_CONNECTION. The recommendation is unavailable when the risk is absent or the impact is unavailable, including when the impact assessment has not been returned. That result is not NO_ACTION. riskLevel and riskExplanation are the connection risk on this response.")
public record ShipmentRecommendation(
		@Schema(description = "True when both inputs were read and one response was selected, including a recommendation that no action is needed.")
		boolean available,
		@Schema(description = "Why no response was selected. Null when a response was selected.", nullable = true,
				example = "A recommendation is not available because the impact assessment has not been returned.")
		String unavailable,
		@Schema(description = "The selected response. Null when the recommendation is unavailable.", nullable = true)
		ShipmentRecommendedResponse response,
		@Schema(description = "The connection-risk level on this read. Null when that risk is absent.", nullable = true,
				example = "LOW")
		ShipmentRisk.Level riskLevel,
		@Schema(description = "The connection-risk explanation on this read. Null when that risk is absent. It is that explanation, not a replacement.", nullable = true)
		String riskExplanation,
		@Schema(description = "The impact result used with the connection risk.")
		ShipmentRecommendationImpact impact) {

	static final String RISK_NOT_ASSESSED = "A recommendation is not available because the connection risk is not assessed.";
	static final String IMPACT_NOT_RETURNED = "A recommendation is not available because the impact assessment has not been returned.";
	static final String BOTH_NOT_RETURNED = "A recommendation is not available because the connection risk is not assessed and the impact assessment has not been returned.";
	static final String IMPACT_UNAVAILABLE = "A recommendation is not available because the impact assessment is unavailable. ";
	static final String BOTH_UNAVAILABLE = "A recommendation is not available because the connection risk is not assessed and the impact assessment is unavailable. ";

	public ShipmentRecommendation {
		if (impact == null) {
			throw new IllegalArgumentException("A recommendation needs the impact it used");
		}
		if (riskLevel == null) {
			if (riskExplanation != null) {
				throw new IllegalArgumentException("A missing connection risk has no explanation");
			}
		} else if (riskExplanation == null || riskExplanation.isBlank()) {
			throw new IllegalArgumentException("A connection risk on the recommendation keeps its explanation");
		}
		if (available) {
			if (unavailable != null || response == null || riskLevel == null
					|| impact.outcome() == ShipmentRecommendationImpact.Outcome.UNAVAILABLE) {
				throw new IllegalArgumentException("A completed recommendation has one response and both inputs");
			}
			if (response.code() != codeFor(riskLevel, impact.outcome())) {
				throw new IllegalArgumentException("The response does not match the risk and the impact");
			}
			if (response.wording().equals(riskExplanation)) {
				throw new IllegalArgumentException("The response does not replace the connection-risk explanation");
			}
		} else if (unavailable == null || unavailable.isBlank() || response != null
				|| !unavailable.equals(sentence(riskLevel, impact))) {
			throw new IllegalArgumentException("An unavailable recommendation names why it was not selected");
		} else if (riskLevel != null && impact.outcome() != ShipmentRecommendationImpact.Outcome.UNAVAILABLE) {
			throw new IllegalArgumentException("A completed pair selects a response");
		}
	}

	/**
	 * Selects the recorded response from the connection risk already calculated for this read.
	 * A null risk has not been assessed. The shipment detail passes
	 * {@link ShipmentRecommendationImpact#notReturned()} because this read does not carry an impact assessment.
	 */
	static ShipmentRecommendation of(ShipmentRisk risk, ShipmentRecommendationImpact impact) {
		if (impact == null) {
			throw new IllegalArgumentException("A recommendation needs the impact it used");
		}
		ShipmentRisk.Level level = risk == null ? null : risk.level();
		String explanation = risk == null ? null : risk.explanation();
		if (risk != null && impact.outcome() != ShipmentRecommendationImpact.Outcome.UNAVAILABLE) {
			return new ShipmentRecommendation(true, null,
					ShipmentRecommendedResponse.of(codeFor(level, impact.outcome())), level, explanation, impact);
		}
		return new ShipmentRecommendation(false, sentence(level, impact), null, level, explanation, impact);
	}

	private static ShipmentRecommendedResponse.Code codeFor(ShipmentRisk.Level level,
			ShipmentRecommendationImpact.Outcome outcome) {
		if (outcome == ShipmentRecommendationImpact.Outcome.UNAVAILABLE) {
			throw new IllegalArgumentException("An unavailable impact does not select a response");
		}
		boolean noImpact = outcome == ShipmentRecommendationImpact.Outcome.NO_PREDICTED_IMPACT;
		return switch (level) {
			case LOW -> noImpact ? ShipmentRecommendedResponse.Code.NO_ACTION
					: ShipmentRecommendedResponse.Code.MONITOR_CONSTRAINTS;
			case MEDIUM -> noImpact ? ShipmentRecommendedResponse.Code.NOTE_TIMING
					: ShipmentRecommendedResponse.Code.REVIEW_PLAN;
			case HIGH -> noImpact ? ShipmentRecommendedResponse.Code.WATCH_CONNECTION
					: ShipmentRecommendedResponse.Code.PREPARE_INTERVENTION;
			case CRITICAL -> noImpact ? ShipmentRecommendedResponse.Code.CONFIRM_CONNECTION
					: ShipmentRecommendedResponse.Code.ACT_ON_CONNECTION;
		};
	}

	private static String sentence(ShipmentRisk.Level riskLevel, ShipmentRecommendationImpact impact) {
		boolean riskMissing = riskLevel == null;
		boolean impactMissing = impact.outcome() == ShipmentRecommendationImpact.Outcome.UNAVAILABLE;
		if (!riskMissing && !impactMissing) {
			throw new IllegalArgumentException("A completed recommendation has no unavailable sentence");
		}
		if (riskMissing && impactMissing) {
			if (ShipmentRecommendationImpact.NOT_RETURNED.equals(impact.detail())) {
				return BOTH_NOT_RETURNED;
			}
			return BOTH_UNAVAILABLE + impact.detail();
		}
		if (riskMissing) {
			return RISK_NOT_ASSESSED;
		}
		if (ShipmentRecommendationImpact.NOT_RETURNED.equals(impact.detail())) {
			return IMPACT_NOT_RETURNED;
		}
		return IMPACT_UNAVAILABLE + impact.detail();
	}
}
