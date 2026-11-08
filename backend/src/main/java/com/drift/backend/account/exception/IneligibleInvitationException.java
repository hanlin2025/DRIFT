package com.drift.backend.account.exception;

public class IneligibleInvitationException extends RuntimeException {

	public static final String WITHDRAWN = "This invitation is no longer valid.";

	public IneligibleInvitationException() {
		super("This invitation is invalid, expired, or no longer available. Ask for a new invitation.");
	}

	public IneligibleInvitationException(String message) {
		super(message);
	}
}
