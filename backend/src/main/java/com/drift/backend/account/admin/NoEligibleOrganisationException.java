package com.drift.backend.account.admin;

public class NoEligibleOrganisationException extends RuntimeException {

	public static final String MESSAGE = "No eligible organisation is available.";

	public NoEligibleOrganisationException() {
		super(MESSAGE);
	}
}
