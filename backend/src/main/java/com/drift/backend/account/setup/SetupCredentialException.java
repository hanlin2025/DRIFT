package com.drift.backend.account.setup;

public class SetupCredentialException extends RuntimeException {

	public static final String MESSAGE = "The setup credential is missing or invalid.";

	public SetupCredentialException() {
		super(MESSAGE);
	}
}
