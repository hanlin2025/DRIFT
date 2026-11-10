package com.drift.backend.account.signup;

import java.time.Instant;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
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

	public CompanySignupStore(UserAccountRepository users, AccountRoleRepository roles, CompanyRepository companies,
			CompanySignupAuditRepository audits, EmailVerificationTokens verificationTokens, VerificationMailDispatcher mail) {
		this.mail = mail;
		this.users = users;
		this.roles = roles;
		this.companies = companies;
		this.audits = audits;
		this.verificationTokens = verificationTokens;
	}

	/**
	 * Creates the inactive company, its pending administrator, a verification token and the REGISTERED audit
	 * row in one transaction. Returns empty, and writes nothing, when the email is already registered.
	 *
	 * @throws DuplicateOrganisationException when another company already has the code
	 * @throws SignupEmailTakenException when a concurrent signup committed the same email first
	 */
	@Transactional
	public Optional<CreatedSignup> create(OrganisationIdentity identity, String fullName, String email,
			String passwordHash) {
		if (users.existsByEmailIgnoreCase(email)) {
			return Optional.empty();
		}
		if (companies.findByNormalisedCode(identity.code()).isPresent()) {
			throw new DuplicateOrganisationException();
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
		mail.sendAfterCommit(account.getEmail(), token);
		return Optional.of(new CreatedSignup(account.getId(), company.getId(), token));
	}

	/** Whether the email belongs to an account awaiting verification as the administrator of the company with this code. */
	@Transactional(readOnly = true)
	public boolean isPendingAdministratorOf(String email, String companyCode) {
		return users.findByEmailIgnoreCase(email)
				.filter(account -> account.getStatus() == com.drift.backend.account.AccountStatus.PENDING_VERIFICATION)
				.map(UserAccount::getCompany)
				.filter(company -> company != null && companyCode.equals(company.getCode()))
				.isPresent();
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

	public record CreatedSignup(Long userId, Long companyId, EmailVerificationTokens.IssuedVerification verification) {
	}
}
