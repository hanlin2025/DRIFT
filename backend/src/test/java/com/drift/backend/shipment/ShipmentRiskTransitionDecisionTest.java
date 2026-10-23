package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShipmentRiskTransitionDecisionTest {

	@Test
	void evaluatesInitialBaselinesForEveryRiskLevelIncludingCritical() {
		for (ShipmentRisk.Level level : ShipmentRisk.Level.values()) {
			ShipmentRisk risk = risk(level);
			assertThat(ShipmentRiskTransitionDecision.decide(ShipmentRiskBaselineMode.SILENT_FIRST_VALID, null, risk))
					.isEqualTo(ShipmentRiskTransitionDecision.Type.BASELINE);
			assertThat(ShipmentRiskTransitionDecision.decide(ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID, null, risk))
					.isEqualTo(ShipmentRiskTransitionDecision.Type.INITIAL);
		}
	}

	@Test
	void createsOnlyStrictlyIncreasingTransitionsIncludingCritical() {
		assertThat(decide(ShipmentRisk.Level.LOW, ShipmentRisk.Level.MEDIUM))
				.isEqualTo(ShipmentRiskTransitionDecision.Type.RISE);
		assertThat(decide(ShipmentRisk.Level.MEDIUM, ShipmentRisk.Level.HIGH))
				.isEqualTo(ShipmentRiskTransitionDecision.Type.RISE);
		assertThat(decide(ShipmentRisk.Level.HIGH, ShipmentRisk.Level.CRITICAL))
				.isEqualTo(ShipmentRiskTransitionDecision.Type.RISE);
		assertThat(decide(ShipmentRisk.Level.LOW, ShipmentRisk.Level.CRITICAL))
				.isEqualTo(ShipmentRiskTransitionDecision.Type.RISE);
		assertThat(decide(ShipmentRisk.Level.HIGH, ShipmentRisk.Level.HIGH))
				.isEqualTo(ShipmentRiskTransitionDecision.Type.NONE);
		assertThat(decide(ShipmentRisk.Level.HIGH, ShipmentRisk.Level.MEDIUM))
				.isEqualTo(ShipmentRiskTransitionDecision.Type.NONE);
	}

	@Test
	void incompleteRiskNeverCreatesOrChangesATransitionDecision() {
		assertThat(ShipmentRiskTransitionDecision.decide(ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID,
				ShipmentRisk.Level.HIGH, null)).isEqualTo(ShipmentRiskTransitionDecision.Type.INCOMPLETE);
		assertThat(ShipmentRiskTransitionDecision.decide(ShipmentRiskBaselineMode.SILENT_FIRST_VALID,
				null, null)).isEqualTo(ShipmentRiskTransitionDecision.Type.INCOMPLETE);
	}

	private static ShipmentRiskTransitionDecision.Type decide(ShipmentRisk.Level previous, ShipmentRisk.Level current) {
		return ShipmentRiskTransitionDecision.decide(ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID, previous,
				risk(current));
	}

	private static ShipmentRisk risk(ShipmentRisk.Level level) {
		return new ShipmentRisk(level, level + " explanation");
	}
}
