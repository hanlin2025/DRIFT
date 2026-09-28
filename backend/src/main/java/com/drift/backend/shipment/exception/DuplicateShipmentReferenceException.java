package com.drift.backend.shipment.exception;

public class DuplicateShipmentReferenceException extends RuntimeException {

	public static final String MESSAGE = "A shipment with this reference already exists for your company";

	public DuplicateShipmentReferenceException() {
		super(MESSAGE);
	}
}
