package com.drift.backend.ais.position.exception;

public class InvalidAisPositionMessageException extends RuntimeException {

	public InvalidAisPositionMessageException(String message) {
		super(message);
	}

	public InvalidAisPositionMessageException(String message, Throwable cause) {
		super(message, cause);
	}
}
