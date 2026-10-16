package com.drift.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.admin.AccountStatusAudit;
import com.drift.backend.account.admin.AccountStatusAuditRepository;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountStatusSchemaIntegrationTests {

	@Autowired UserAccountRepository accounts;
	@Autowired AccountRoleRepository roles;
	@Autowired CompanyRepository companies;
	@Autowired AccountStatusAuditRepository audits;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager entityManager;

	@Test
	void newAccountsAreActive() {
		UserAccount account = accounts.saveAndFlush(account(harbourline()));
		entityManager.clear();

		assertThat(accounts.findById(account.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
	}

	@Test
	void rowsInsertedWithoutAStatusDefaultToActive() {
		String email = uniqueEmail();
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'Alice Tan', ?, 'hash', id FROM roles WHERE code = 'IMPORTER'
				""", email);

		assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email))
				.isEqualTo("ACTIVE");
	}

	@Test
	void rejectsUnknownStatuses() {
		UserAccount account = accounts.saveAndFlush(account(harbourline()));

		assertThatThrownBy(() -> jdbc.update("UPDATE users SET status = 'DELETED' WHERE id = ?", account.getId()))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void recordsStatusChanges() {
		Company harbourline = harbourline();
		UserAccount operator = accounts.save(account(harbourline));
		UserAccount target = accounts.save(account(harbourline));
		Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		AccountStatusAudit audit = audits.saveAndFlush(
				new AccountStatusAudit(operator, target, AccountStatus.ACTIVE, harbourline, recordedAt));
		entityManager.clear();

		AccountStatusAudit stored = audits.findById(audit.getId()).orElseThrow();
		assertThat(stored.getOperator().getId()).isEqualTo(operator.getId());
		assertThat(stored.getTarget().getId()).isEqualTo(target.getId());
		assertThat(stored.getOrganisationId()).isEqualTo(harbourline.getId());
		assertThat(stored.getPreviousStatus()).isEqualTo(AccountStatus.ACTIVE);
		assertThat(stored.getNewStatus()).isEqualTo(AccountStatus.ACTIVE);
		assertThat(stored.getRecordedAt()).isEqualTo(recordedAt);
	}

	private Company harbourline() {
		return companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
	}

	private UserAccount account(Company company) {
		return new UserAccount("Alice Tan", uniqueEmail(), "hash",
				roles.findByCode(Role.FREIGHT_FORWARDER).orElseThrow(), company);
	}

	private static String uniqueEmail() {
		return "cdg153-" + UUID.randomUUID() + "@example.com";
	}
}
