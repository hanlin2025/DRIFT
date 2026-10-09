package com.drift.backend.shipment.exception;

public class StaleShipmentVersionException extends RuntimeException {

	public static final String MESSAGE = "This shipment was updated by another user. Refresh it and try again.";

	public StaleShipmentVersionException() {
		super(MESSAGE);
	}
}
