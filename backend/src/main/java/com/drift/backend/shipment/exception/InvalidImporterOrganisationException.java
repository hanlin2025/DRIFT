package com.drift.backend.shipment.exception;

public class InvalidImporterOrganisationException extends RuntimeException {

	public static final String MESSAGE = "Invalid or inactive importer organisation selected.";

	public InvalidImporterOrganisationException() {
		super(MESSAGE);
	}
}
