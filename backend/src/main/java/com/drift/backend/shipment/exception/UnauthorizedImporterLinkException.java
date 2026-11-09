package com.drift.backend.shipment.exception;

public class UnauthorizedImporterLinkException extends RuntimeException {

	public static final String MESSAGE = "Your organisation is not authorized to link shipments to this importer";

	public UnauthorizedImporterLinkException() {
		super(MESSAGE);
	}
}
