package com.drift.backend.account.admin.exception;

public class InvalidAssignmentException extends RuntimeException {

	public static final String MESSAGE = "Invalid role or organisation selected.";

	public InvalidAssignmentException() {
		super(MESSAGE);
	}
}
