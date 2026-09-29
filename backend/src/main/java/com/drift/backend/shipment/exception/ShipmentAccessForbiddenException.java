package com.drift.backend.shipment.exception;

public class ShipmentAccessForbiddenException extends RuntimeException {

	public static final String MESSAGE = "Your account must belong to an active company to view shipments";

	public ShipmentAccessForbiddenException() {
		super(MESSAGE);
	}
}
