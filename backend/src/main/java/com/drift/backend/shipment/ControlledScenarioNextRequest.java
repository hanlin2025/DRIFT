package com.drift.backend.shipment;

import jakarta.validation.constraints.NotBlank;

public record ControlledScenarioNextRequest(@NotBlank String expectedSnapshotId) { }
