package com.drift.backend.account.signup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.passwordreset.ResetTokens;
import com.drift.backend.company.Company;

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
	public void sendAfterCommit(UserAccount account, EmailVerificationTokens.IssuedVerification issued) {
		// Read the details now, inside the transaction, so the email shows exactly what was committed.
		Company company = account.getCompany();
		EmailVerificationMailer.VerificationEmail email = new EmailVerificationMailer.VerificationEmail(account.getEmail(),
				ResetTokens.link(linkBaseUrl, issued.rawToken()), tokens.ttl(), account.getFullName(),
				company.getName(), company.getCode());
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("A verification email must be sent after a transaction commits");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				deliver(email, issued.userId());
			}
		});
	}

	private void deliver(EmailVerificationMailer.VerificationEmail email, Long userId) {
		try {
			mailer.sendVerificationLink(email);
		} catch (RuntimeException ex) {
			log.error("Email verification message could not be sent for user {} ({})", userId,
					ex.getClass().getSimpleName());
		}
	}
}
