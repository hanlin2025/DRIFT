package com.drift.backend.account.passwordreset;

public record ForgotPasswordResponse(String message) {

	public static final String MESSAGE = "If this email is registered, a reset link has been sent";

	public static ForgotPasswordResponse generic() {
		return new ForgotPasswordResponse(MESSAGE);
	}
}
