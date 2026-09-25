package com.drift.backend.registration;

import com.drift.backend.registration.exception.InvalidRegistrationException;

public final class PasswordPolicy {

	public static final String MESSAGE =
			"Password must be at least 8 characters and include an uppercase letter, a lowercase letter, and a number, with no spaces";

	private static final int MIN_LENGTH = 8;
	private static final int BCRYPT_MAX_BYTES = 72;

	private PasswordPolicy() {
	}

	public static void check(String password) {
		if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new InvalidRegistrationException("Password must be at most 72 bytes");
		}
		if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
			throw new InvalidRegistrationException("Password must be at least 8 characters");
		}
		boolean upper = false;
		boolean lower = false;
		boolean digit = false;
		for (int i = 0; i < password.length(); ) {
			int codePoint = password.codePointAt(i);
			if (Character.isWhitespace(codePoint)) {
				throw new InvalidRegistrationException("Password must not contain spaces");
			} else if (Character.isUpperCase(codePoint)) {
				upper = true;
			} else if (Character.isLowerCase(codePoint)) {
				lower = true;
			} else if (Character.isDigit(codePoint)) {
				digit = true;
			}
			i += Character.charCount(codePoint);
		}
		if (!upper || !lower || !digit) {
			throw new InvalidRegistrationException(MESSAGE);
		}
	}
}
