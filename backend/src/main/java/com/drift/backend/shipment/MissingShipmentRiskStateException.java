package com.drift.backend.shipment;

public class MissingShipmentRiskStateException extends RuntimeException {

	public MissingShipmentRiskStateException(Long shipmentId) {
		super("Shipment risk state could not be initialized for shipment " + shipmentId);
	}
}
