package com.drift.backend.account.exception;

public class ProfileForbiddenException extends RuntimeException {

	public static final String MESSAGE = "You can only update your own profile.";

	public ProfileForbiddenException() {
		super(MESSAGE);
	}
}
