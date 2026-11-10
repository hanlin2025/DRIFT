package com.drift.backend.account.exception;

public class EmailNotVerifiedException extends RuntimeException {

	/** Lets clients tell this 403 apart from any other without parsing the message. */
	public static final String CODE = "EMAIL_NOT_VERIFIED";

	public static final String MESSAGE = "Verify your email before signing in. You can request a new verification link.";

	public EmailNotVerifiedException() {
		super(MESSAGE);
	}
}
