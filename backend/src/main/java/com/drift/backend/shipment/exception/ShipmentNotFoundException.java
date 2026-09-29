package com.drift.backend.shipment.exception;

public class ShipmentNotFoundException extends RuntimeException {

	public static final String MESSAGE = "Shipment not found";

	public ShipmentNotFoundException() {
		super(MESSAGE);
	}
}
