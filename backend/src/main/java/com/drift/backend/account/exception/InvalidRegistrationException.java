package com.drift.backend.account.exception;

public class InvalidRegistrationException extends RuntimeException {

	public InvalidRegistrationException(String message) {
		super(message);
	}
}
