package com.drift.backend.account.signup;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.passwordreset.ResetTokens;

/**
 * Issues email verification tokens. Only the SHA-256 hash is stored; the raw 43-character token is returned
 * once so it can be put in the emailed link.
 */
@Component
public class EmailVerificationTokens {

	private final EmailVerificationTokenRepository tokens;
	private final Duration ttl;

	public EmailVerificationTokens(EmailVerificationTokenRepository tokens,
			@Value("${app.signup.verification-ttl}") Duration ttl) {
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalStateException("app.signup.verification-ttl must be positive");
		}
		this.tokens = tokens;
		this.ttl = ttl;
	}

	/** Must run inside the caller's transaction so the token commits or rolls back with the account. */
	public IssuedVerification issue(UserAccount account) {
		String rawToken = ResetTokens.generate();
		EmailVerificationToken saved = tokens.saveAndFlush(
				new EmailVerificationToken(account, ResetTokens.hash(rawToken), Instant.now().plus(ttl)));
		return new IssuedVerification(saved.getId(), account.getId(), rawToken);
	}

	public Duration ttl() {
		return ttl;
	}

	public record IssuedVerification(Long id, Long userId, String rawToken) {
		@Override
		public String toString() {
			return "IssuedVerification[id=" + id + ", userId=" + userId + ", rawToken=redacted]";
		}
	}
}
