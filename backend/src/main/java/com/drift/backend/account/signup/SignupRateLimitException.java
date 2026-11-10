package com.drift.backend.account.signup;

public class SignupRateLimitException extends RuntimeException {

	public static final String MESSAGE = "Too many requests. Try again later.";

	public SignupRateLimitException() {
		super(MESSAGE);
	}
}
