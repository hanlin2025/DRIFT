package com.drift.backend.shipment.exception;

public class ShipmentConstraintEditForbiddenException extends RuntimeException {

	public static final String MESSAGE =
			"Only the linked importer company can change this shipment's business constraints";

	public ShipmentConstraintEditForbiddenException() {
		super(MESSAGE);
	}
}
