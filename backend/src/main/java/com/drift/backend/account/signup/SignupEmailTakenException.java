package com.drift.backend.account.signup;

/**
 * Internal signal that a concurrent signup took the email first. The transaction rolls back and the
 * caller still answers with the generic accepted response.
 */
class SignupEmailTakenException extends RuntimeException {

	SignupEmailTakenException() {
		super("The email was registered concurrently", null, false, false);
	}
}
