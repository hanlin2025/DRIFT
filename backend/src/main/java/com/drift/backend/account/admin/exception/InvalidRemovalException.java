package com.drift.backend.account.admin.exception;

public class InvalidRemovalException extends RuntimeException {

	public static final String ORGANISATION = "Your organisation is missing or inactive.";
	public static final String OWN_ACCOUNT = "You cannot remove your own account.";

	public InvalidRemovalException(String message) {
		super(message);
	}
}
