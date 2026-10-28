package com.drift.backend.shipment.exception;

public class ShipmentRestoreForbiddenException extends RuntimeException {

	public static final String MESSAGE = "You do not have permission to restore this shipment.";

	public ShipmentRestoreForbiddenException() {
		super(MESSAGE);
	}
}
