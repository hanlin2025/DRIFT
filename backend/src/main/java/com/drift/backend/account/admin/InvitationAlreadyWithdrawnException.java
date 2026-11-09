package com.drift.backend.account.admin;

public class InvitationAlreadyWithdrawnException extends RuntimeException {

	public static final String MESSAGE = "This invitation is already withdrawn.";

	public InvitationAlreadyWithdrawnException() {
		super(MESSAGE);
	}
}
