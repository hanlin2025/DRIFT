package com.drift.backend.account.admin.exception;

public class AssignedUserNotFoundException extends RuntimeException {

	public static final String MESSAGE = "User not found.";

	public AssignedUserNotFoundException() {
		super(MESSAGE);
	}
}
