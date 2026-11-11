package com.drift.backend.shipment;

import java.util.UUID;

/** Published only for a successful CHANGE-to-WORSENED command, after the session monitor is released. */
record ControlledScenarioWorsenedEvent(UUID sessionId, UUID epochId, long revision,
		String scenarioShipmentKey, ControlledScenarioSnapshot snapshot) { }
