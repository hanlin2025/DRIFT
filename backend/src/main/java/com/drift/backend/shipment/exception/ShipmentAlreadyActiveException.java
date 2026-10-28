package com.drift.backend.shipment.exception;

public class ShipmentAlreadyActiveException extends RuntimeException {

	public static final String MESSAGE = "This shipment is already active.";

	public ShipmentAlreadyActiveException() {
		super(MESSAGE);
	}
}
