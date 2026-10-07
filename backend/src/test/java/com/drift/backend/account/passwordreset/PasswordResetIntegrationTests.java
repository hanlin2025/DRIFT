package com.drift.backend.account.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.exception.InvalidCredentialsException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.account.registration.PasswordPolicy;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PasswordResetIntegrationTests.CapturingMail.class)
class PasswordResetIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager entityManager;
	@Autowired PasswordEncoder passwords;
	@Autowired CapturingPasswordResetMailer mailer;

	private String email;

	@BeforeEach
	void account() {
		mailer.reset();
		email = "cdg114-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role)
				VALUES ('Alex', ?, 'hash', 'FREIGHT_FORWARDER')
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
		assertThat(expiresAt).isAfter(before.plus(Duration.ofMinutes(14)));
		assertThat(expiresAt).isBefore(before.plus(Duration.ofMinutes(16)));
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
				INSERT INTO users (full_name, email, password_hash, role)
				VALUES ('Other', ?, 'hash', 'IMPORTER')
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

	@Test
	void replacesThePasswordWhenTheLinkIsUnusedAndUnexpired() throws Exception {
		String token = issuedToken();
		String password = "NewPass1";
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, password)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ResetPasswordResponse.MESSAGE))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());

		String hash = passwordHash();
		assertThat(passwords.matches(password, hash)).isTrue();
		assertThat(passwords.matches("hash", hash)).isFalse();
		assertThat(usedAt()).isNotNull();
		assertThat(mailer.links).hasSize(1);

		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "OtherPass2")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidResetTokenException.MESSAGE))
				.andExpect(jsonPath("$.errors.token").value(InvalidResetTokenException.MESSAGE));
		assertThat(passwordHash()).isEqualTo(hash);
	}

	@Test
	void rejectsAnExpiredOrUnknownLinkWithoutChangingThePassword() throws Exception {
		String token = issuedToken();
		jdbc.update("""
				UPDATE password_reset_tokens AS t
				SET expires_at = now() - interval '1 day'
				FROM users AS u
				WHERE u.id = t.user_id AND u.email = ?
				""", email);
		entityManager.clear();
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "NewPass1")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidResetTokenException.MESSAGE));
		assertThat(passwordHash()).isEqualTo("hash");
		assertThat(usedAt()).isNull();

		String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(tampered, "NewPass1")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.token").value(InvalidResetTokenException.MESSAGE));
		assertThat(passwordHash()).isEqualTo("hash");
		assertThat(usedAt()).isNull();
	}

	@Test
	void rejectsAPasswordThatBreaksThePolicyAndKeepsTheLinkUnused() throws Exception {
		String token = issuedToken();
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "short1A")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value("Password must be at least 8 characters"));
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "Password")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value(PasswordPolicy.MESSAGE));
		assertThat(passwordHash()).isEqualTo("hash");
		assertThat(usedAt()).isNull();
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "NewPass1")))
				.andExpect(status().isOk());
	}

	@Test
	void aSuccessfulResetEndsSessionsIssuedBeforeIt() throws Exception {
		jdbc.update("UPDATE users SET password_hash = ? WHERE email = ?", passwords.encode("Example123"), email);
		entityManager.clear();
		String first = loginToken("Example123");
		String second = loginToken("Example123");
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + first))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email));

		String token = issuedToken();
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "NewPass1")))
				.andExpect(status().isOk());
		entityManager.clear();
		assertThat(jdbc.queryForObject("SELECT password_changed_at FROM users WHERE email = ?", Instant.class, email))
				.isNotNull();

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + first))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + second))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));

		String renewed = loginToken("NewPass1");
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + renewed))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email));
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, "Example123")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(InvalidCredentialsException.MESSAGE));
	}

	@Test
	void rejectsAMissingOrMalformedReset() throws Exception {
		String token = issuedToken();
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"NewPass1\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.token").value("Reset link is required"));
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"%s\"}".formatted(token)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value("Password is required"));
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody("not-a-reset-link", "NewPass1")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.token").value(InvalidResetTokenException.MESSAGE));
		assertThat(passwordHash()).isEqualTo("hash");
		assertThat(usedAt()).isNull();
	}

	private String loginToken(String password) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, password)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private static String loginBody(String accountEmail, String password) {
		return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(accountEmail, password);
	}

	private void request(String accountEmail) throws Exception {
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(accountEmail)))
				.andExpect(status().isOk());
	}

	private String issuedToken() throws Exception {
		request(email);
		return tokenFrom(mailer.links.get(mailer.links.size() - 1));
	}

	private static String resetBody(String token, String password) {
		return "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password);
	}

	private String passwordHash() {
		return jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
	}

	private Instant usedAt() {
		return jdbc.queryForObject("""
				SELECT t.used_at FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ?
				""", Instant.class, email);
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
