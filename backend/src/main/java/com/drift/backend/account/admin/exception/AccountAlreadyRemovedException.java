package com.drift.backend.account.admin.exception;

public class AccountAlreadyRemovedException extends RuntimeException {

	public static final String MESSAGE = "This account has already been removed.";

	public AccountAlreadyRemovedException() {
		super(MESSAGE);
	}
}
