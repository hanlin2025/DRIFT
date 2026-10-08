package com.drift.backend.shipment.exception;

public class ShipmentImportForbiddenException extends RuntimeException {

	public static final String MESSAGE = "Only a freight forwarder in an active company can import shipments";

	public ShipmentImportForbiddenException() {
		super(MESSAGE);
	}
}
