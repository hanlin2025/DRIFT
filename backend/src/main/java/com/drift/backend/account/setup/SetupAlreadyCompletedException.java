package com.drift.backend.account.setup;

public class SetupAlreadyCompletedException extends RuntimeException {

	public static final String MESSAGE = "This installation already has an administrator.";

	public SetupAlreadyCompletedException() {
		super(MESSAGE);
	}
}
