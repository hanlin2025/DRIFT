package com.drift.backend.account.admin.exception;

public class LastAdministratorException extends RuntimeException {

	public static final String MESSAGE = "A company must keep at least one active administrator.";

	public LastAdministratorException() {
		super(MESSAGE);
	}
}
