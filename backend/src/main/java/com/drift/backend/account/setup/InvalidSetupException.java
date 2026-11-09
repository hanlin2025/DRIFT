package com.drift.backend.account.setup;

public class InvalidSetupException extends RuntimeException {

	private final String field;

	public InvalidSetupException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}
}
