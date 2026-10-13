package com.drift.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProfilePreferenceIntegrationTests {

	@Autowired JdbcTemplate jdbc;
	@Autowired UserAccountRepository users;

	@Test
	void existingInsertsReceiveTheDefaultPreferenceWithoutASecondPasswordColumn() {
		String email = insert("Alice Tan");

		Preference stored = preference(email);
		assertThat(stored.enabled()).isFalse();
		assertThat(stored.level()).isEqualTo("HIGH");
		assertThat(stored.fullName()).isEqualTo("Alice Tan");
		assertThat(columnLength("full_name")).isEqualTo(200);
		assertThat(columnLength("email")).isEqualTo(320);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				WHERE table_name = 'users' AND column_name = 'password_hash'
				""", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				WHERE table_name = 'users'
				  AND column_name IN ('photo', 'profile_photo', 'password', 'password_hash_previous')
				""", Integer.class)).isZero();
	}

	@Test
	void savedNameAndPreferenceReloadFromTheSameUserRow() {
		String email = insert("Alice Tan");
		UserAccount account = users.findByEmailIgnoreCase(email).orElseThrow();
		String hash = account.getPasswordHash();
		Role role = account.getRole();
		Long companyId = account.getCompany().getId();

		account.rename("  Nur Aisyah  ");
		account.chooseRiskNotifications(true, RiskNotificationLevel.CRITICAL);
		users.saveAndFlush(account);

		Preference stored = preference(email);
		assertThat(stored.fullName()).isEqualTo("Nur Aisyah");
		assertThat(stored.email()).isEqualTo(email);
		assertThat(stored.enabled()).isTrue();
		assertThat(stored.level()).isEqualTo("CRITICAL");
		assertThat(stored.passwordHash()).isEqualTo(hash);
		assertThat(stored.sessionGeneration()).isZero();
		UserAccount reloaded = users.findByEmailIgnoreCase(email).orElseThrow();
		assertThat(reloaded.getRole()).isEqualTo(role);
		assertThat(reloaded.getCompany().getId()).isEqualTo(companyId);
		assertThat(reloaded.notifiesFor(RiskNotificationLevel.CRITICAL)).isTrue();
		assertThat(reloaded.notifiesFor(RiskNotificationLevel.HIGH)).isFalse();
	}

	@Test
	void passwordReplacementKeepsOneHashAndARejectedLevelIsNotStored() {
		String email = insert("Alice Tan");
		UserAccount account = users.findByEmailIgnoreCase(email).orElseThrow();

		account.replacePasswordHash("replacement-hash");
		users.saveAndFlush(account);

		Preference stored = preference(email);
		assertThat(stored.passwordHash()).isEqualTo("replacement-hash");
		assertThat(stored.sessionGeneration()).isEqualTo(1);
		assertThat(stored.level()).isEqualTo("HIGH");
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE users SET minimum_risk_notification_level = 'SEVERE' WHERE email = ?
				""", email)).isInstanceOf(DataAccessException.class);
	}

	private String insert(String fullName) {
		String email = "cdg140-" + UUID.randomUUID() + "@example.com";
		Long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, 'stored-hash', id, ?
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", fullName, email, companyId);
		return email;
	}

	private Preference preference(String email) {
		return jdbc.queryForObject("""
				SELECT full_name, email, password_hash, risk_email_notifications_enabled,
				       minimum_risk_notification_level, session_generation
				FROM users WHERE email = ?
				""", (row, number) -> new Preference(row.getString(1), row.getString(2), row.getString(3),
						row.getBoolean(4), row.getString(5), row.getLong(6)), email);
	}

	private int columnLength(String column) {
		return jdbc.queryForObject("""
				SELECT character_maximum_length FROM information_schema.columns
				WHERE table_name = 'users' AND column_name = ?
				""", Integer.class, column);
	}

	private record Preference(String fullName, String email, String passwordHash, boolean enabled, String level,
			long sessionGeneration) {
	}
}
