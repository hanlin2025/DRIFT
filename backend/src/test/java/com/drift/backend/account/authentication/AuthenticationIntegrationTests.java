package com.drift.backend.account.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.exception.InvalidCredentialsException;
import com.drift.backend.account.exception.SessionEndedException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthenticationIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired UserAccountRepository users;
	@Value("${app.jwt.secret}") String secret;

	private String email;
	private Long companyId;

	@BeforeEach
	void account() {
		email = "cdg12-" + UUID.randomUUID() + "@example.com";
		companyId = jdbc.queryForObject("SELECT id FROM organisations WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		createAccount(email, "FREIGHT_FORWARDER", companyId);
	}

	@Test
	void loginReturnsRoleCompanyAndAnExpiringSession() throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content(login(email.toUpperCase(), "Example123")))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.fullName").value("Alice Tan"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.company.code").value("HARBOURLINE_DEMO"))
				.andExpect(jsonPath("$.company.name").value("Harbourline Logistics (Demo)"))
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andReturn();

		String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.company.id").value(companyId))
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void unknownUserAndWrongPasswordLookTheSame() throws Exception {
		assertUnauthorized(login(email, "WrongPass1"));
		assertUnauthorized(login("nobody-" + email, "Example123"));
	}

	@Test
	void missingFieldsAreRejected() throws Exception {
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("Email is required"))
				.andExpect(jsonPath("$.errors.password").value("Password is required"));
	}

	@Test
	void missingOrBrokenSessionCannotOpenTheCurrentUser() throws Exception {
		mvc.perform(get("/api/session"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(get("/api/session").header("Authorization", "Bearer not-a-session"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void importerLoginOpensAnImporterSession() throws Exception {
		String importer = "cdg14-importer-" + UUID.randomUUID() + "@example.com";
		Long importerCompany = jdbc.queryForObject("SELECT id FROM organisations WHERE code = 'STRAITS_FRESH_DEMO'", Long.class);
		createAccount(importer, "IMPORTER", importerCompany);

		String token = tokenFor(importer, "Example123");
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(importer))
				.andExpect(jsonPath("$.role").value("IMPORTER"))
				.andExpect(jsonPath("$.company.id").value(importerCompany));
	}

	@Test
	void wrongPasswordShowsTheInvalidCredentialsMessageWithoutASession() throws Exception {
		assertUnauthorized(login(email, "WrongPass1"));
		assertThat(InvalidCredentialsException.MESSAGE).isEqualTo("Invalid email or password.");
	}

	@Test
	void accountThatDoesNotExistCannotSignIn() throws Exception {
		String missing = "cdg14-missing-" + UUID.randomUUID() + "@example.com";
		assertUnauthorized(login(missing, "Example123"));
		assertThat(users.existsByEmailIgnoreCase(missing)).isFalse();
	}

	@Test
	void eachMissingFieldIsReportedOnItsOwn() throws Exception {
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value("Password is required"))
				.andExpect(jsonPath("$.errors.email").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(login("   ", "Example123")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("Email is required"))
				.andExpect(jsonPath("$.errors.password").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void signedOutRequestsToProtectedPathsExposeNoData() throws Exception {
		mvc.perform(get("/api/shipments"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void expiredSessionMustAuthenticateAgain() throws Exception {
		Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
		JwtSessionTokens pastTokens = new JwtSessionTokens(secret, Duration.ofHours(1).toMillis(),
				Clock.fixed(issuedAt, ZoneOffset.UTC));
		String expired = pastTokens.issue(users.findByEmailIgnoreCase(email).orElseThrow()).token();

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath("$.email").doesNotExist());

		String renewed = tokenFor(email, "Example123");
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + renewed))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email));
	}

	private void createAccount(String accountEmail, String role, Long company) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, organisation_id)
				SELECT 'Alice Tan', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", accountEmail, passwords.encode("Example123"), company, role);
	}

	private String tokenFor(String accountEmail, String password) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content(login(accountEmail, password)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private void assertUnauthorized(String body) throws Exception {
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(InvalidCredentialsException.MESSAGE))
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	private static String login(String email, String password) {
		return """
				{"email":"%s","password":"%s"}
				""".formatted(email, password);
	}
}
