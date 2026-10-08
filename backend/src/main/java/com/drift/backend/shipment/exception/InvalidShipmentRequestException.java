package com.drift.backend.shipment.exception;

import java.util.Map;

public class InvalidShipmentRequestException extends RuntimeException {

	public static final String MESSAGE = "Shipment information is missing or invalid";

	private final Map<String, String> errors;

	public InvalidShipmentRequestException(Map<String, String> errors) {
		super(MESSAGE);
		this.errors = Map.copyOf(errors);
	}

	public Map<String, String> getErrors() {
		return errors;
	}
}
