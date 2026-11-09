package com.drift.backend.account.passwordreset;

public class InvalidResetTokenException extends RuntimeException {

	public static final String MESSAGE =
			"This reset link is invalid or has expired. Please request a new one.";

	public InvalidResetTokenException() {
		super(MESSAGE);
	}
}
