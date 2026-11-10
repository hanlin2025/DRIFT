package com.drift.backend.organisation.exception;

public class OrganisationCreationForbiddenException extends RuntimeException {

	public static final String MESSAGE = "Your account must belong to an active company to add an organisation";

	public OrganisationCreationForbiddenException() {
		super(MESSAGE);
	}
}
