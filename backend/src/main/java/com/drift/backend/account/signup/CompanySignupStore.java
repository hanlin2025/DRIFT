package com.drift.backend.account.signup;

import java.time.Instant;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.organisation.OrganisationIdentity;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;

@Service
public class CompanySignupStore {

	private final UserAccountRepository users;
	private final AccountRoleRepository roles;
	private final CompanyRepository companies;
	private final CompanySignupAuditRepository audits;
	private final EmailVerificationTokens verificationTokens;
	private final VerificationMailDispatcher mail;
	private final EmailVerificationTokenRepository tokenRows;

	public CompanySignupStore(UserAccountRepository users, AccountRoleRepository roles, CompanyRepository companies,
			CompanySignupAuditRepository audits, EmailVerificationTokens verificationTokens, VerificationMailDispatcher mail,
			EmailVerificationTokenRepository tokenRows) {
		this.mail = mail;
		this.tokenRows = tokenRows;
		this.users = users;
		this.roles = roles;
		this.companies = companies;
		this.audits = audits;
		this.verificationTokens = verificationTokens;
	}

	/**
	 * Creates the inactive company, its pending administrator, a verification token and the REGISTERED audit
	 * row in one transaction. Writes nothing when the email is already registered, and says whether that
	 * account is a pending signup that a newer submission may replace.
	 *
	 * @throws DuplicateOrganisationException when the code belongs to any company other than the inactive
	 *         company of this email's own pending signup, whether or not the email is registered
	 * @throws SignupEmailTakenException when a concurrent signup committed the same email first
	 */
	@Transactional
	public Outcome create(OrganisationIdentity identity, String fullName, String email,
			String passwordHash) {
		// The code is checked before the email, so a taken code answers 409 whether or not the email is registered.
		UserAccount existing = users.findByEmailIgnoreCase(email).orElse(null);
		Long ownPendingCompanyId = existing != null && existing.getStatus() == AccountStatus.PENDING_VERIFICATION
				&& existing.getCompany() != null ? existing.getCompany().getId() : null;
		companies.findByNormalisedCode(identity.code())
				.filter(holder -> !holder.getId().equals(ownPendingCompanyId))
				.ifPresent(holder -> {
					throw new DuplicateOrganisationException();
				});
		if (existing != null) {
			return ownPendingCompanyId != null ? Outcome.PENDING : Outcome.EXISTS;
		}
		Company company;
		try {
			company = companies.saveAndFlush(Company.createInactive(identity.code(), identity.name()));
		} catch (DataIntegrityViolationException ex) {
			throw new DuplicateOrganisationException();
		}
		AccountRole admin = roles.findByCode(Role.ADMIN)
				.orElseThrow(() -> new IllegalStateException("Role is not configured"));
		UserAccount account;
		try {
			account = users.saveAndFlush(UserAccount.pendingAdministrator(fullName, email, passwordHash, admin, company));
		} catch (DataIntegrityViolationException ex) {
			if (isEmailConflict(ex)) {
				throw new SignupEmailTakenException();
			}
			throw ex;
		}
		company.assignCreator(account);
		companies.saveAndFlush(company);
		EmailVerificationTokens.IssuedVerification token = verificationTokens.issue(account);
		audits.saveAndFlush(new CompanySignupAudit(account, company, SignupEvent.REGISTERED, Instant.now()));
		mail.sendAfterCommit(account, token);
		return Outcome.CREATED;
	}

	/**
	 * Replaces a pending signup with a newer submission for the same email, in one transaction: the user's name
	 * and password, the inactive company's code and name, and a new verification link (earlier links stop
	 * working). Only the mailbox owner can then activate the latest submission. Does nothing, and returns false,
	 * when the account is no longer pending or its company is not an inactive company with only this user.
	 *
	 * @throws DuplicateOrganisationException when another company holds the code, including a concurrent signup
	 */
	@Transactional
	public boolean replacePending(OrganisationIdentity identity, String fullName, String email, String passwordHash) {
		UserAccount account = users.findForSignupReplacement(email).orElse(null);
		if (account == null || account.getStatus() != AccountStatus.PENDING_VERIFICATION || account.getCompany() == null) {
			return false;
		}
		Company company = companies.findForSignupReplacement(account.getCompany().getId()).orElse(null);
		if (company == null || company.isActive() || users.countByCompany_Id(company.getId()) != 1) {
			return false;
		}
		companies.findByNormalisedCode(identity.code())
				.filter(holder -> !holder.getId().equals(company.getId()))
				.ifPresent(holder -> {
					throw new DuplicateOrganisationException();
				});
		account.replacePendingSignup(fullName, passwordHash);
		company.replacePendingIdentity(identity.code(), identity.name());
		try {
			companies.saveAndFlush(company);
		} catch (DataIntegrityViolationException ex) {
			throw new DuplicateOrganisationException();
		}
		users.saveAndFlush(account);
		tokenRows.deleteAllUnused(account.getId());
		EmailVerificationTokens.IssuedVerification token = verificationTokens.issue(account);
		audits.saveAndFlush(new CompanySignupAudit(account, company, SignupEvent.RESUBMITTED, Instant.now()));
		mail.sendAfterCommit(account, token);
		return true;
	}

	public enum Outcome {
		/** A new company and pending administrator were created. */
		CREATED,
		/** The email belongs to an active or removed account; nothing changes. */
		EXISTS,
		/** The email belongs to a pending signup, which a newer submission may replace. */
		PENDING
	}

	private static boolean isEmailConflict(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& ("users_email_key".equals(violation.getConstraintName())
					|| "users_email_lower_key".equals(violation.getConstraintName()))) {
				return true;
			}
		}
		return false;
	}

}
