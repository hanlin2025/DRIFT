package com.drift.backend.account.passwordreset;

public class PasswordResetRateLimitException extends RuntimeException {

	public static final String MESSAGE = "Too many password reset requests. Try again later.";

	public PasswordResetRateLimitException() {
		super(MESSAGE);
	}
}
