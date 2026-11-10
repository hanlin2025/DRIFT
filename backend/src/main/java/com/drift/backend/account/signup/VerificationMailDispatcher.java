package com.drift.backend.account.signup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.drift.backend.account.passwordreset.ResetTokens;

/**
 * Sends a verification email once the transaction that created the token has committed. A delivery failure is
 * logged without the token or link and never affects the stored account or the HTTP response.
 */
@Component
public class VerificationMailDispatcher {

	private static final Logger log = LoggerFactory.getLogger(VerificationMailDispatcher.class);

	private final EmailVerificationMailer mailer;
	private final EmailVerificationTokens tokens;
	private final String linkBaseUrl;

	public VerificationMailDispatcher(EmailVerificationMailer mailer, EmailVerificationTokens tokens,
			@Value("${app.signup.link-base-url}") String linkBaseUrl) {
		this.mailer = mailer;
		this.tokens = tokens;
		this.linkBaseUrl = linkBaseUrl;
	}

	/** Must be called inside the transaction that stored the token. */
	public void sendAfterCommit(String email, EmailVerificationTokens.IssuedVerification issued) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("A verification email must be sent after a transaction commits");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				deliver(email, issued);
			}
		});
	}

	private void deliver(String email, EmailVerificationTokens.IssuedVerification issued) {
		try {
			mailer.sendVerificationLink(email, ResetTokens.link(linkBaseUrl, issued.rawToken()), tokens.ttl());
		} catch (RuntimeException ex) {
			log.error("Email verification message could not be sent for user {} ({})", issued.userId(),
					ex.getClass().getSimpleName());
		}
	}
}
