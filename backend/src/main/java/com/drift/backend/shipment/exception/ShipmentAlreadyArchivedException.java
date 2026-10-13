package com.drift.backend.shipment.exception;

public class ShipmentAlreadyArchivedException extends RuntimeException {

	public static final String MESSAGE = "Shipment is already removed or does not exist.";

	public ShipmentAlreadyArchivedException() {
		super(MESSAGE);
	}
}
