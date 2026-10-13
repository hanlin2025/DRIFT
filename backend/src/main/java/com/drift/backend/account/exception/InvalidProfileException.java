package com.drift.backend.account.exception;

public class InvalidProfileException extends RuntimeException {

	public static final String NAME_REQUIRED = "Full name is required";
	public static final String NAME_TOO_LONG = "Full name must be at most 200 characters";
	public static final String RISK_LEVEL = "Risk level must be LOW, MEDIUM, HIGH, or CRITICAL.";
	public static final String MISSING = "Profile information is missing or invalid";
	public static final String LOCKED = "Email, company and role cannot be changed.";
	public static final String CURRENT_INCORRECT = "Current password is incorrect.";
	public static final String CURRENT_REQUIRED = "Enter your current password to set a new one.";
	public static final String NEW_REQUIRED = "Enter a new password.";
	public static final String PASSWORD_FIELDS = "Set a new password with currentPassword and newPassword.";

	public InvalidProfileException(String message) {
		super(message);
	}
}
