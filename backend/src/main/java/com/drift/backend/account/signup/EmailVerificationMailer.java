package com.drift.backend.account.signup;

import java.time.Duration;

public interface EmailVerificationMailer {

	/**
	 * The details of the submission the link activates, so the mailbox owner can recognise a signup that is
	 * not theirs.
	 */
	record VerificationEmail(String to, String link, Duration validFor, String fullName, String companyName,
			String companyCode) {
		@Override
		public String toString() {
			return "VerificationEmail[redacted]";
		}
	}

	void sendVerificationLink(VerificationEmail email);
}
