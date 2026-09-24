package com.drift.backend.registration;

public class IneligibleInvitationException extends RuntimeException {

	public IneligibleInvitationException() {
		super("This email is not eligible to register");
	}
}
