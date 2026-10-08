package com.drift.backend.shipment.exception;

public class ShipmentImportNotFoundException extends RuntimeException {

	public static final String MESSAGE = "Import not found";

	public ShipmentImportNotFoundException() {
		super(MESSAGE);
	}
}
