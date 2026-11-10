package com.drift.backend.account.exception;

public class EmailNotVerifiedException extends RuntimeException {

	public static final String MESSAGE = "Verify your email before signing in. You can request a new verification link.";

	public EmailNotVerifiedException() {
		super(MESSAGE);
	}
}
