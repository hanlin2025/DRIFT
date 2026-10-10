package com.drift.backend.account.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PasswordResetIntegrationTests.CapturingMail.class)
class PasswordResetIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired CapturingPasswordResetMailer mailer;

	private String email;

	@BeforeEach
	void account() {
		mailer.reset();
		email = "cdg114-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'Alex', ?, 'hash', id
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", email);
	}

	@Test
	void sendsAResetLinkAndStoresOnlyTheHash() throws Exception {
		Instant before = Instant.now();
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(email.toUpperCase())))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE))
				.andExpect(jsonPath("$.token").doesNotExist());

		assertThat(mailer.links).hasSize(1);
		String token = tokenFrom(mailer.links.get(0));
		assertThat(token).hasSize(43);
		String hash = ResetTokens.hash(token);
		assertThat(jdbc.queryForObject("""
				SELECT token_hash FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NULL
				""", String.class, email)).isEqualTo(hash);
		Instant expiresAt = jdbc.queryForObject("""
				SELECT expires_at FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NULL
				""", Instant.class, email);
		assertThat(expiresAt).isAfter(before.plus(Duration.ofMinutes(59)));
		assertThat(expiresAt).isBefore(before.plus(Duration.ofMinutes(61)));
		assertThat(jdbc.queryForObject("SELECT token_hash = ? FROM password_reset_tokens WHERE token_hash = ?",
				Boolean.class, token, hash)).isFalse();
	}

	@Test
	void unknownEmailGetsTheSameResponseAndSendsNothing() throws Exception {
		String missing = "missing-" + email;
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(missing)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE));
		assertThat(mailer.links).isEmpty();
		assertThat(countTokens(missing)).isZero();
		assertThat(countTokens(email)).isZero();
	}

	@Test
	void aNewRequestRetiresThePreviousUnusedLink() throws Exception {
		request(email);
		request(email);
		assertThat(mailer.links).hasSize(2);
		assertThat(countTokens(email)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NOT NULL
				""", Integer.class, email)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT token_hash FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NULL
				""", String.class, email)).isEqualTo(ResetTokens.hash(tokenFrom(mailer.links.get(1))));
	}

	@Test
	void oneAccountRequestDoesNotRetireAnotherAccountsLink() throws Exception {
		String other = "other-" + email;
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'Other', ?, 'hash', id
				FROM roles WHERE code = 'IMPORTER'
				""", other);
		request(email);
		request(other);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email IN (?, ?) AND t.used_at IS NULL
				""", Integer.class, email, other)).isEqualTo(2);
	}

	@Test
	void aMailFailureDoesNotLeaveANewLinkOrRetireThePreviousOne() throws Exception {
		request(email);
		String first = tokenFrom(mailer.links.get(0));
		mailer.fail = true;
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(email)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE));
		assertThat(mailer.links).hasSize(1);
		assertThat(jdbc.queryForObject("""
				SELECT token_hash FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NULL
				""", String.class, email)).isEqualTo(ResetTokens.hash(first));
		assertThat(countTokens(email)).isEqualTo(1);
	}

	@Test
	void rejectsAMissingOrInvalidEmailWithoutSending() throws Exception {
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("Email is required"));
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("Email must be a valid email address"));
		assertThat(mailer.links).isEmpty();
		assertThat(countTokens(email)).isZero();
	}

	private void request(String accountEmail) throws Exception {
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(accountEmail)))
				.andExpect(status().isOk());
	}

	private int countTokens(String accountEmail) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ?
				""", Integer.class, accountEmail);
	}

	private static String tokenFrom(String link) {
		String marker = "#token=";
		int start = link.indexOf(marker);
		assertThat(start).isGreaterThan(0);
		return link.substring(start + marker.length());
	}

	@TestConfiguration
	static class CapturingMail {

		@Bean
		@Primary
		CapturingPasswordResetMailer passwordResetMailer() {
			return new CapturingPasswordResetMailer();
		}
	}

	static final class CapturingPasswordResetMailer implements PasswordResetMailer {

		private final List<String> links = new ArrayList<>();
		private boolean fail;

		@Override
		public void sendResetLink(String email, String resetLink, Duration validFor) {
			if (fail) {
				throw new IllegalStateException("mail transport failed");
			}
			links.add(resetLink);
		}

		void reset() {
			links.clear();
			fail = false;
		}
	}
}
