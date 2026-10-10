package com.drift.backend.shipment.exception;

public class MissingShipmentInformationException extends RuntimeException {

	public static final String MESSAGE = "Shipment information is missing or invalid";

	public MissingShipmentInformationException() {
		super(MESSAGE);
	}
}
