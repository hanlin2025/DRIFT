package com.drift.backend.shipment.exception;

public class InvalidShipmentFileException extends RuntimeException {

	public static final String MESSAGE = "Invalid file format. Please download and use the provided CSV template.";

	public InvalidShipmentFileException() {
		super(MESSAGE);
	}
}
