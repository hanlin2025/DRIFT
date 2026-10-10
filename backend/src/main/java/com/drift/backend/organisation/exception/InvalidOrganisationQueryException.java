package com.drift.backend.organisation.exception;

public class InvalidOrganisationQueryException extends RuntimeException {

	public static final String TYPE = "Organisation type must be importer";
	public static final String PAGE = "Page must be zero or greater";
	public static final String SIZE = "Page size must be between 1 and 100";

	public InvalidOrganisationQueryException(String message) {
		super(message);
	}
}
