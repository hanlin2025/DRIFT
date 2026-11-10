package com.drift.backend.account.signup;

import java.time.Duration;

public interface EmailVerificationMailer {

	void sendVerificationLink(String email, String verificationLink, Duration validFor);
}
