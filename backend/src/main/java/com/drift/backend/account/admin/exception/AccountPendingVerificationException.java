package com.drift.backend.account.admin.exception;

public class AccountPendingVerificationException extends RuntimeException {

	public static final String MESSAGE = "This account is awaiting email verification.";

	public AccountPendingVerificationException() {
		super(MESSAGE);
	}
}
