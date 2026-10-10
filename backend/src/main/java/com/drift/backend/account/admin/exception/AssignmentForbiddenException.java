package com.drift.backend.account.admin.exception;

public class AssignmentForbiddenException extends RuntimeException {

	public static final String MESSAGE = "Only an administrator can assign users";

	public AssignmentForbiddenException() {
		super(MESSAGE);
	}
}
