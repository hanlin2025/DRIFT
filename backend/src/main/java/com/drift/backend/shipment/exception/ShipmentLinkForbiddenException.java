package com.drift.backend.shipment.exception;

public class ShipmentLinkForbiddenException extends RuntimeException {

	public static final String MESSAGE = "Only a freight forwarder in the shipment's company can link it to an importer";

	public ShipmentLinkForbiddenException() {
		super(MESSAGE);
	}
}
