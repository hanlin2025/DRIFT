package com.drift.backend.account.passwordreset;

public record ResetPasswordResponse(String message) {

	public static final String MESSAGE = "Your password has been reset";

	public static ResetPasswordResponse completed() {
		return new ResetPasswordResponse(MESSAGE);
	}
}
