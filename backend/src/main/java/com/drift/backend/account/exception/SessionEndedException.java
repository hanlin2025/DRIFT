package com.drift.backend.account.exception;

public class SessionEndedException extends RuntimeException {

	public static final String MESSAGE = "Your session has ended. Log in again.";

	public SessionEndedException() {
		super(MESSAGE);
	}
}
