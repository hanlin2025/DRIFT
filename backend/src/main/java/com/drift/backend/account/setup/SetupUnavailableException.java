package com.drift.backend.account.setup;

public class SetupUnavailableException extends RuntimeException {

	public static final String MESSAGE = "First-administrator setup is not available.";

	public SetupUnavailableException() {
		super(MESSAGE);
	}
}
