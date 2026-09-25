package com.drift.backend.account.exception;

public class InvalidCredentialsException extends RuntimeException {

	public static final String MESSAGE = "The email or password is incorrect.";

	public InvalidCredentialsException() {
		super(MESSAGE);
	}
}
