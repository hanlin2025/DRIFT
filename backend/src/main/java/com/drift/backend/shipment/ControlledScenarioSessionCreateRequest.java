package com.drift.backend.shipment;

import jakarta.validation.constraints.NotNull;

/** The shipment ID selects a configured demo binding; it does not grant access by itself. */
public record ControlledScenarioSessionCreateRequest(@NotNull Long anchorShipmentId) { }
