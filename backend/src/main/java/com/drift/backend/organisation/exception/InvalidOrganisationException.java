package com.drift.backend.organisation.exception;

public class InvalidOrganisationException extends RuntimeException {

	public static final String CODE = "Enter an organisation code using letters, digits, and underscores.";
	public static final String NAME = "Enter an organisation name.";

	public InvalidOrganisationException(String message) {
		super(message);
	}
}
