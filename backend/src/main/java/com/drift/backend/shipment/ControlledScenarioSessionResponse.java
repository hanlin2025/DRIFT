package com.drift.backend.shipment;

import java.util.List;
import java.util.UUID;

/** Versioned session envelope; snapshot is null until Start commits BASELINE. */
public record ControlledScenarioSessionResponse(
		String apiVersion,
		UUID sessionId,
		String scenarioId,
		String scenarioContractVersion,
		ControlledScenarioSessionState state,
		UUID epochId,
		long revision,
		ControlledScenarioCapabilities capabilities,
		List<ShipmentBinding> shipments,
		ControlledScenarioSnapshot snapshot) {

	public ControlledScenarioSessionResponse {
		shipments = List.copyOf(shipments);
	}

	public record ShipmentBinding(String scenarioShipmentKey, Long shipmentId,
			String shipmentReference, String scenarioShipmentReference) { }
}
