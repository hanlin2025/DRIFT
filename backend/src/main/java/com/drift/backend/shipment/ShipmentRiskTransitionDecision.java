package com.drift.backend.shipment;

/** Pure transition policy; persistence and risk calculation remain in {@link ShipmentRiskEvaluator}. */
final class ShipmentRiskTransitionDecision {

	enum Type {
		INCOMPLETE,
		BASELINE,
		INITIAL,
		RISE,
		NONE
	}

	private ShipmentRiskTransitionDecision() {
	}

	static Type decide(ShipmentRiskBaselineMode baselineMode, ShipmentRisk.Level previousRiskLevel, ShipmentRisk risk) {
		if (risk == null) {
			return Type.INCOMPLETE;
		}
		if (previousRiskLevel == null) {
			return baselineMode == ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID ? Type.INITIAL : Type.BASELINE;
		}
		return risk.level().compareTo(previousRiskLevel) > 0 ? Type.RISE : Type.NONE;
	}
}
