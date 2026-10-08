package com.drift.backend.account.passwordreset;

import java.time.Duration;

public interface PasswordResetMailer {

	void sendResetLink(String email, String resetLink, Duration validFor);
}
