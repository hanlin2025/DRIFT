package com.drift.backend.account.signup;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.passwordreset.ResetTokens;
import com.drift.backend.company.Company;

@Service
public class EmailVerificationStore {

	private final EmailVerificationTokenRepository tokenRows;
	private final EmailVerificationTokens tokens;
	private final UserAccountRepository users;
	private final CompanySignupAuditRepository audits;
	private final VerificationMailDispatcher mail;

	public EmailVerificationStore(EmailVerificationTokenRepository tokenRows, EmailVerificationTokens tokens,
			UserAccountRepository users, CompanySignupAuditRepository audits, VerificationMailDispatcher mail) {
		this.tokenRows = tokenRows;
		this.tokens = tokens;
		this.users = users;
		this.audits = audits;
		this.mail = mail;
	}

	/**
	 * Uses the token, activates the account and its company, and records EMAIL_VERIFIED, all in one transaction.
	 * Returns false for an unknown, used, expired or replaced token, and changes nothing.
	 */
	@Transactional
	public boolean verify(String rawToken) {
		EmailVerificationToken token = tokenRows.findForVerification(ResetTokens.hash(rawToken)).orElse(null);
		Instant now = Instant.now();
		if (token == null || token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
			return false;
		}
		UserAccount account = token.getUser();
		if (account.getStatus() != AccountStatus.PENDING_VERIFICATION) {
			return false;
		}
		token.markUsed(now);
		tokenRows.saveAndFlush(token);
		tokenRows.deleteAllUnused(account.getId());
		account.verifyEmail();
		Company company = account.getCompany();
		company.activate();
		users.saveAndFlush(account);
		audits.saveAndFlush(new CompanySignupAudit(account, company, SignupEvent.EMAIL_VERIFIED, now));
		return true;
	}

	/**
	 * Replaces any unused verification link of a pending account with a new one, emailed after commit.
	 * Does nothing for an unknown, active or removed account.
	 */
	@Transactional
	public void reissue(String email) {
		UserAccount account = users.findByEmailIgnoreCase(email).orElse(null);
		if (account == null || account.getStatus() != AccountStatus.PENDING_VERIFICATION) {
			return;
		}
		tokenRows.deleteAllUnused(account.getId());
		mail.sendAfterCommit(account, tokens.issue(account));
	}
}
