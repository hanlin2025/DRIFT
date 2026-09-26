package com.drift.backend.account.exception;

public class IneligibleInvitationException extends RuntimeException {
	public IneligibleInvitationException() {
		super("This invitation is invalid, expired, or no longer available. Ask for a new invitation.");
	}
}
