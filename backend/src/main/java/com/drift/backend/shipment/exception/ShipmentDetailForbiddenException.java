package com.drift.backend.shipment.exception;

public class ShipmentDetailForbiddenException extends RuntimeException {

	public static final String MESSAGE = "You do not have access to this shipment";

	public ShipmentDetailForbiddenException() {
		super(MESSAGE);
	}
}
