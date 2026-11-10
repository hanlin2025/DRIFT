package com.drift.backend.account.admin.exception;

public class SelfAssignmentException extends RuntimeException {

	public static final String MESSAGE = "You cannot change your own organisation or role.";

	public SelfAssignmentException() {
		super(MESSAGE);
	}
}
