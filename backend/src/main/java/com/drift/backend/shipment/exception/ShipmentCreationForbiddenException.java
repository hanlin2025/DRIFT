package com.drift.backend.shipment.exception;

public class ShipmentCreationForbiddenException extends RuntimeException {

	public static final String MESSAGE = "Your account must belong to an active company to create shipments";

	public ShipmentCreationForbiddenException() {
		super(MESSAGE);
	}
}
