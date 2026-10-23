package com.drift.backend.shipment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistence operations for future evaluators; no risk calculation or alert policy is applied here. */
@Service
public class ShipmentRiskStateStore {

	private final ShipmentRiskStateRepository states;

	public ShipmentRiskStateStore(ShipmentRiskStateRepository states) {
		this.states = states;
	}

	/**
	 * Locks the shipment's state for an atomic evaluation. Missing state is always initialized silently,
	 * never inferred to mean a newly created shipment.
	 */
	@Transactional
	public ShipmentRiskState lockOrInitializeSilently(Long shipmentId) {
		if (shipmentId == null) {
			throw new IllegalArgumentException("Shipment id is required");
		}
		states.initializeMissingSilently(shipmentId);
		return states.findByShipmentIdForUpdate(shipmentId)
				.orElseThrow(() -> new MissingShipmentRiskStateException(shipmentId));
	}
}
