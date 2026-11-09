package com.drift.backend.account.admin;

public class InvitationExpiredException extends RuntimeException {

	public static final String MESSAGE = "This invitation has expired.";

	public InvitationExpiredException() {
		super(MESSAGE);
	}
}
