package com.drift.backend.account.admin;

public class InvitationConflictException extends RuntimeException {

	public static final String MESSAGE = "An invitation for this email is already being sent. Try again.";

	public InvitationConflictException() {
		super(MESSAGE);
	}
}
