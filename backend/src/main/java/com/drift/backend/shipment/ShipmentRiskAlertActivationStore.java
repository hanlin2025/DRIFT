package com.drift.backend.shipment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Acquires the shared gate before any write or evaluation that can affect risk-alert history. */
@Service
public class ShipmentRiskAlertActivationStore {
	private final ShipmentRiskAlertActivationRepository activations;

	ShipmentRiskAlertActivationStore(ShipmentRiskAlertActivationRepository activations) {
		this.activations = activations;
	}

	@Transactional
	public ShipmentRiskAlertActivation lockForRiskInput() {
		return activations.lockForRiskInput().orElseThrow(() -> new IllegalStateException("Risk-alert activation row is missing"));
	}

	@Transactional
	ShipmentRiskAlertActivation lockForActivation() {
		return activations.lockForActivation().orElseThrow(() -> new IllegalStateException("Risk-alert activation row is missing"));
	}
}
