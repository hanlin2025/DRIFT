package com.drift.backend.shipment;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ControlledScenarioCommandRequest(@NotNull UUID expectedEpochId,
		@NotNull @Min(0) Long expectedRevision) { }
