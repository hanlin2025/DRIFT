package com.drift.backend.account.signup;

public class InvalidVerificationTokenException extends RuntimeException {

	public static final String MESSAGE = "This verification link is invalid or has expired. Please request a new one.";

	public InvalidVerificationTokenException() {
		super(MESSAGE);
	}
}
