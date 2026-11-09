package com.drift.backend.maritimeai;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Snapshot binding supplied by the UI; all values are checked against the authorized session. */
public record ScenarioMaritimeAiRequest(
		@NotBlank String scenarioShipmentKey,
		@NotNull UUID expectedEpochId,
		@PositiveOrZero long expectedRevision,
		@NotNull UUID expectedSnapshotId,
		@NotBlank String expectedSimulationCutoffUtc) { }
