package com.drift.backend.account.admin;

public class InvalidInvitationException extends RuntimeException {

	private final String field;

	public InvalidInvitationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}
}
