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
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PasswordResetIntegrationTests.CapturingMail.class)
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetIntegrationTests {

	private static final String FAKE_TOKEN = "b".repeat(43);

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

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + first))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + second))
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

	@Test
	void aRemovedAccountsLinkIsRejectedLikeAnInvalidOne() throws Exception {
		String token = issuedToken();
		String unknown = invalidLinkResponse(FAKE_TOKEN);
		long generation = sessionGeneration();
		jdbc.update("UPDATE users SET status = 'REMOVED' WHERE email = ?", email);
		entityManager.clear();

		String removed = mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "NewPass1")))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.errors.token").value(InvalidResetTokenException.MESSAGE))
				.andReturn().getResponse().getContentAsString();
		assertThat(removed).isEqualTo(unknown);
		assertThat(passwordHash()).isEqualTo("hash");
		assertThat(usedAt()).isNull();
		assertThat(sessionGeneration()).isEqualTo(generation);
	}

	@Test
	void aLinkReplacedByANewerRequestIsRejected() throws Exception {
		String first = issuedToken();
		String second = issuedToken();
		String unknown = invalidLinkResponse(FAKE_TOKEN);

		assertThat(invalidLinkResponse(first)).isEqualTo(unknown);
		assertThat(passwordHash()).isEqualTo("hash");
		assertThat(usedAt()).isNull();

		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(second, "NewPass1")))
				.andExpect(status().isOk());
	}

	@Test
	void aSuccessfulResetRemovesTheUsersOtherUnusedLinks() throws Exception {
		String older = ResetTokens.generate();
		insertUnusedToken(email, older);
		String token = ResetTokens.generate();
		insertUnusedToken(email, token);
		String newer = ResetTokens.generate();
		insertUnusedToken(email, newer);
		String other = "other-" + email;
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'Other', ?, 'hash', id
				FROM roles WHERE code = 'IMPORTER'
				""", other);
		insertUnusedToken(other, ResetTokens.generate());
		assertThat(countTokens(email)).isEqualTo(3);

		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "NewPass1")))
				.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NULL
				""", Integer.class, email)).isZero();
		assertThat(countTokens(email)).isEqualTo(1);
		assertThat(usedAt()).isNotNull();
		assertThat(countTokens(other)).isEqualTo(1);
		assertThat(invalidLinkResponse(newer)).isEqualTo(invalidLinkResponse(FAKE_TOKEN));
		assertThat(invalidLinkResponse(older)).isEqualTo(invalidLinkResponse(FAKE_TOKEN));
	}

	@Test
	void neitherTheTokenNorThePasswordIsLogged(CapturedOutput output) throws Exception {
		String token = issuedToken();
		String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
		String weak = "weakpass9";
		String chosen = "Chosen9Secret";
		Logger web = (Logger) LoggerFactory.getLogger("org.springframework.web");
		Logger app = (Logger) LoggerFactory.getLogger("com.drift.backend");
		Level webLevel = web.getLevel();
		Level appLevel = app.getLevel();
		web.setLevel(Level.DEBUG);
		app.setLevel(Level.DEBUG);
		try {
			mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(tampered, chosen)))
					.andExpect(status().isBadRequest());
			mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, weak)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.password").value(PasswordPolicy.MESSAGE));
			mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, chosen)))
					.andExpect(status().isOk());
			mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, chosen)))
					.andExpect(status().isBadRequest());
		} finally {
			web.setLevel(webLevel);
			app.setLevel(appLevel);
		}

		// The request body is logged at DEBUG, so the capture really sees it, but only in redacted form.
		assertThat(output.getAll()).contains("ResetPasswordRequest[redacted]");
		assertThat(output.getAll()).doesNotContain(token, tampered, weak, chosen);
	}

	@Test
	void documentsBothEndpointsAsPublicWithTheirResponses() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/auth/forgot-password'].post.summary").value("Request a password reset link"))
				.andExpect(jsonPath("$.paths['/api/auth/forgot-password'].post.tags[0]").value("Password reset"))
				.andExpect(jsonPath("$.paths['/api/auth/forgot-password'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/auth/forgot-password'].post.responses['200']").exists())
				.andExpect(jsonPath("$.paths['/api/auth/forgot-password'].post.responses['400']").exists())
				.andExpect(jsonPath("$.paths['/api/auth/forgot-password'].post.responses['429']").exists())
				.andExpect(jsonPath("$.paths['/api/auth/reset-password'].post.summary")
						.value("Choose a new password with a reset link"))
				.andExpect(jsonPath("$.paths['/api/auth/reset-password'].post.tags[0]").value("Password reset"))
				.andExpect(jsonPath("$.paths['/api/auth/reset-password'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/auth/reset-password'].post.responses['200']").exists())
				.andExpect(jsonPath("$.paths['/api/auth/reset-password'].post.responses['400']").exists())
				.andExpect(jsonPath("$.paths['/api/auth/reset-password'].post.responses['429']").exists())
				.andExpect(jsonPath("$.paths['/api/auth/reset-password'].post.requestBody.content['application/json'].schema.$ref")
						.value("#/components/schemas/ResetPasswordRequest"))
				.andExpect(jsonPath("$.components.schemas.ResetPasswordRequest.properties.password").exists())
				.andExpect(jsonPath("$.components.schemas.ResetPasswordRequest.properties.token.pattern")
						.value("[A-Za-z0-9_-]{43}"));
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

	private void insertUnusedToken(String accountEmail, String rawToken) {
		jdbc.update("""
				INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
				SELECT id, ?, now() + interval '1 hour' FROM users WHERE email = ?
				""", ResetTokens.hash(rawToken), accountEmail);
	}

	private String invalidLinkResponse(String token) throws Exception {
		return mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(resetBody(token, "NewPass1")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidResetTokenException.MESSAGE))
				.andExpect(jsonPath("$.errors.token").value(InvalidResetTokenException.MESSAGE))
				.andReturn().getResponse().getContentAsString();
	}

	private long sessionGeneration() {
		return jdbc.queryForObject("SELECT session_generation FROM users WHERE email = ?", Long.class, email);
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
