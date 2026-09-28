package com.drift.backend.account.exception;

public class InvalidCredentialsException extends RuntimeException {

	public static final String MESSAGE = "Invalid email or password.";

	public InvalidCredentialsException() {
		super(MESSAGE);
	}
}
