package com.drift.backend.account.admin;

public class InvitationAccessDeniedException extends RuntimeException {

	public static final String MESSAGE = "You cannot manage this invitation.";

	public InvitationAccessDeniedException() {
		super(MESSAGE);
	}
}
