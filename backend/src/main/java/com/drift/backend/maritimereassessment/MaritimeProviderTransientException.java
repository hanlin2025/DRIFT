package com.drift.backend.maritimereassessment;

/** A provider failure that may be tried once more inside the same review, inside the cost and rate limits. */
public class MaritimeProviderTransientException extends RuntimeException {

	public MaritimeProviderTransientException(String message) {
		super(message);
	}

	public MaritimeProviderTransientException(String message, Throwable cause) {
		super(message, cause);
	}
}
