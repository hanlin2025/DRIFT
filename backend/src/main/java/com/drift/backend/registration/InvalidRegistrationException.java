package com.drift.backend.registration;

public class InvalidRegistrationException extends RuntimeException {

	public InvalidRegistrationException(String message) {
		super(message);
	}
}
