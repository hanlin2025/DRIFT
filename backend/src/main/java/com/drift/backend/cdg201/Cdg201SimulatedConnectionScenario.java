package com.drift.backend.cdg201;

/** Explicitly simulated, non-mutating connection comparison for the replay UI. */
public record Cdg201SimulatedConnectionScenario(
		String state,
		int originalConnectionMarginMinutes,
		int revisedConnectionMarginMinutes,
		int reductionMinutes,
		String simulatedRiskBefore,
		String simulatedRiskAfter,
		String disclaimer) {
}
