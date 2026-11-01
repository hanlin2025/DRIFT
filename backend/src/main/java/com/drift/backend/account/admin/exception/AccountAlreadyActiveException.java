package com.drift.backend.account.admin.exception;

public class AccountAlreadyActiveException extends RuntimeException {

	public static final String MESSAGE = "This account is already active.";

	public AccountAlreadyActiveException() {
		super(MESSAGE);
	}
}
