package com.drift.backend.shipment;

/** Public presentation stages for the CDG-279 controlled scenario. */
public enum ControlledScenarioStage {
	BASELINE,
	CHANGE,
	WORSENED;

	static ControlledScenarioStage fromOrder(int order) {
		return switch (order) {
			case 0 -> BASELINE;
			case 1 -> CHANGE;
			case 2 -> WORSENED;
			default -> throw new IllegalArgumentException("Unknown controlled scenario stage.");
		};
	}
}
