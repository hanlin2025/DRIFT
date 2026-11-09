package com.drift.backend.account.setup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.exception.DuplicateAccountException;
import com.drift.backend.account.registration.PasswordPolicy;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

@Service
public class SetupService {

	static final String ONE_ADMIN = "A company can have only one admin";

	private final UserAccountRepository users;
	private final AccountRoleRepository roles;
	private final CompanyRepository companies;
	private final JdbcTemplate jdbc;
	private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
	private final Clock clock;
	private final String configuredToken;

	@Autowired
	public SetupService(UserAccountRepository users, AccountRoleRepository roles, CompanyRepository companies,
			JdbcTemplate jdbc, org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
			ObjectProvider<Clock> clocks, @Value("${app.setup.token:}") String configuredToken) {
		this(users, roles, companies, jdbc, passwordEncoder, clocks.getIfAvailable(Clock::systemUTC), configuredToken);
	}

	SetupService(UserAccountRepository users, AccountRoleRepository roles, CompanyRepository companies, JdbcTemplate jdbc,
			org.springframework.security.crypto.password.PasswordEncoder passwordEncoder, Clock clock, String configuredToken) {
		this.users = users;
		this.roles = roles;
		this.companies = companies;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
		this.configuredToken = configuredToken;
	}

	@Transactional(readOnly = true)
	public SetupStatusResponse status(String presented) {
		requireCredential(presented);
		if (initialized()) {
			return new SetupStatusResponse(true, List.of());
		}
		List<SetupOrganisationResponse> organisations = companies.findByActiveTrueOrderByNameAscIdAsc().stream()
				.map(SetupOrganisationResponse::from)
				.toList();
		return new SetupStatusResponse(false, organisations);
	}

	@Transactional
	public SetupAccountResponse create(String presented, SetupAdministratorRequest request) {
		requireCredential(presented);
		lockInstallation();
		if (initialized()) {
			throw new SetupAlreadyCompletedException();
		}
		Company organisation = companies.findById(request.organisationId()).filter(Company::isActive)
				.orElseThrow(() -> new InvalidSetupException("organisationId", "Choose an active organisation."));
		PasswordPolicy.check(request.password());
		if (users.existsByEmailIgnoreCase(request.email())) {
			throw new DuplicateAccountException();
		}
		AccountRole role = roles.findByCode(Role.ADMIN)
				.orElseThrow(() -> new IllegalStateException("Administrator role is not configured"));
		UserAccount account = new UserAccount(request.fullName(), request.email(), passwordEncoder.encode(request.password()),
				role, organisation);
		try {
			account = users.saveAndFlush(account);
		} catch (DataIntegrityViolationException ex) {
			if (duplicateEmail(ex)) {
				throw new DuplicateAccountException();
			}
			if (mentions(ex, ONE_ADMIN)) {
				throw new SetupAlreadyCompletedException();
			}
			throw ex;
		}
		recordCompletion(account.getId(), organisation.getId(), clock.instant());
		return SetupAccountResponse.from(account);
	}

	private void requireCredential(String presented) {
		if (!SetupCredential.isConfigured(configuredToken)) {
			throw new SetupUnavailableException();
		}
		if (!SetupCredential.matches(configuredToken, presented)) {
			throw new SetupCredentialException();
		}
	}

	private void lockInstallation() {
		jdbc.execute((Connection connection) -> {
			try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(296309)")) {
				statement.execute();
			} catch (SQLException ex) {
				throw new IllegalStateException("Could not lock first-administrator setup", ex);
			}
			return null;
		});
	}

	private boolean initialized() {
		Integer setups = jdbc.queryForObject("SELECT COUNT(*) FROM installation_setup", Integer.class);
		if (setups != null && setups > 0) {
			return true;
		}
		Integer administrators = jdbc.queryForObject("""
				SELECT COUNT(*) FROM users AS account
				JOIN roles AS role ON role.id = account.role_id
				WHERE role.code = 'ADMIN'
				""", Integer.class);
		return administrators != null && administrators > 0;
	}

	private void recordCompletion(Long administratorId, Long organisationId, Instant completedAt) {
		jdbc.update("""
				INSERT INTO installation_setup (id, administrator_user_id, organisation_id, completed_at)
				VALUES (1, ?, ?, ?)
				""", administratorId, organisationId, Timestamp.from(completedAt));
	}

	private static boolean duplicateEmail(DataIntegrityViolationException failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& ("users_email_key".equals(violation.getConstraintName())
							|| "users_email_lower_key".equals(violation.getConstraintName()))) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentions(Throwable failure, String text) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(text)) {
				return true;
			}
		}
		return false;
	}
}
