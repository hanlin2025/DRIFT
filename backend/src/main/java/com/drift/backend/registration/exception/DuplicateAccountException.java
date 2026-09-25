package com.drift.backend.registration.exception;

public class DuplicateAccountException extends RuntimeException {

	public DuplicateAccountException() {
		super("An account with this email already exists");
	}
}
