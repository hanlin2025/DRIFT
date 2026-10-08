package com.drift.backend.shipment.exception;

public class ShipmentArchiveForbiddenException extends RuntimeException {

	public static final String MESSAGE = "You do not have permission to archive this shipment.";

	public ShipmentArchiveForbiddenException() {
		super(MESSAGE);
	}
}
