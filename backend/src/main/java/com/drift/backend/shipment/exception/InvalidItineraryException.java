package com.drift.backend.shipment.exception;

public class InvalidItineraryException extends RuntimeException {

	public static final String MESSAGE = "Planned feeder-vessel departure must be after planned mother-vessel arrival";

	public InvalidItineraryException() {
		super(MESSAGE);
	}
}
