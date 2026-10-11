package com.drift.backend.organisation.exception;

public class OrganisationAccessForbiddenException extends RuntimeException {

	public static final String MESSAGE = "Your account must belong to an active company to view organisations";

	public OrganisationAccessForbiddenException() {
		super(MESSAGE);
	}
}
