package com.drift.backend.organisation.exception;

public class DuplicateOrganisationException extends RuntimeException {

	public static final String MESSAGE = "An organisation with this code already exists.";

	public DuplicateOrganisationException() {
		super(MESSAGE);
	}
}
