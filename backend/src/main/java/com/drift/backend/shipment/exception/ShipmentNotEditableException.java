package com.drift.backend.shipment.exception;

public class ShipmentNotEditableException extends RuntimeException {

	public static final String MESSAGE = "This shipment cannot be edited in its current status.";

	public ShipmentNotEditableException() {
		super(MESSAGE);
	}
}
