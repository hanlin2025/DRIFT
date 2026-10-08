package com.drift.backend.shipment.exception;

public class InvalidShipmentIdException extends RuntimeException {

	public static final String MESSAGE = "Invalid Shipment ID.";

	public InvalidShipmentIdException() {
		super(MESSAGE);
	}
}
