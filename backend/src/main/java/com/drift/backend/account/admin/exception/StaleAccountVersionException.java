package com.drift.backend.account.admin.exception;

public class StaleAccountVersionException extends RuntimeException {

	public static final String MESSAGE = "This account was changed by someone else. Reload and try again.";

	public StaleAccountVersionException() {
		super(MESSAGE);
	}
}
