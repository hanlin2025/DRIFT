package com.drift.backend.account.admin;

public class InvitationAlreadyAcceptedException extends RuntimeException {

	public static final String MESSAGE = "This invitation has already been accepted.";

	public InvitationAlreadyAcceptedException() {
		super(MESSAGE);
	}
}
