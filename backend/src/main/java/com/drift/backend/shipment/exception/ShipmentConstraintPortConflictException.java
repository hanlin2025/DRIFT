package com.drift.backend.shipment.exception;

public class ShipmentConstraintPortConflictException extends RuntimeException {

	public static final String MESSAGE =
			"The transshipment port changed. Refresh the shipment and confirm the deadline for the current port.";

	public ShipmentConstraintPortConflictException() {
		super(MESSAGE);
	}
}
