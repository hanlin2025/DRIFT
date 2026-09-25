package com.drift.backend.account.exception;

public class DuplicateAccountException extends RuntimeException {

	public DuplicateAccountException() {
		super("An account with this email already exists");
	}
}
