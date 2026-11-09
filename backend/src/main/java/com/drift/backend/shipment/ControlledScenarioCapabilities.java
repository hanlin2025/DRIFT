package com.drift.backend.shipment;

public record ControlledScenarioCapabilities(
		boolean canRead,
		boolean canStart,
		boolean canAdvance,
		boolean canReset,
		boolean canGenerateAssessment) { }
