package com.drift.backend.account.admin;

public class InvitationDeliveryException extends RuntimeException {

	public static final String MESSAGE = "The invitation email could not be sent. No invitation was issued.";

	public InvitationDeliveryException() {
		super(MESSAGE);
	}
}
