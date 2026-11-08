package com.drift.backend.account.admin;

public class InvitationManagementForbiddenException extends RuntimeException {

	public static final String MESSAGE = "You cannot manage invitations for this organisation.";

	public InvitationManagementForbiddenException() {
		super(MESSAGE);
	}
}
