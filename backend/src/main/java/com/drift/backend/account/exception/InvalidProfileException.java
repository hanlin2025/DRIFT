package com.drift.backend.account.exception;

public class InvalidProfileException extends RuntimeException {

	public static final String NAME_REQUIRED = "Full name is required";
	public static final String NAME_TOO_LONG = "Full name must be at most 200 characters";
	public static final String RISK_LEVEL = "Risk level must be LOW, MEDIUM, HIGH, or CRITICAL.";

	public InvalidProfileException(String message) {
		super(message);
	}
}
