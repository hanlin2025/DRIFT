package com.drift.backend.account.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest(properties = "app.setup.token=test-only-setup-token-not-used-outside-tests")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SetupIntegrationTests {

	private static final String CREDENTIAL = "test-only-setup-token-not-used-outside-tests";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager entityManager;

	private Long harbourline;
	private String email;

	@BeforeEach
	void organisation() {
		harbourline = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		email = "cdg309-" + UUID.randomUUID() + "@example.com";
	}

	@Test
	void aFreshInstallCreatesOneAdministratorWhoCanSignIn() throws Exception {
		mvc.perform(get("/api/setup/status").header(SetupController.CREDENTIAL_HEADER, CREDENTIAL))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.initialized").value(false))
				.andExpect(jsonPath("$.organisations[?(@.code == 'HARBOURLINE_DEMO')]").exists());

		MvcResult created = mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(harbourline, "Ada Admin", email, "Example123")))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.fullName").value("Ada Admin"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.organisation.code").value("HARBOURLINE_DEMO"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn();

		assertThat(created.getResponse().getContentAsString()).doesNotContain(CREDENTIAL);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM installation_setup", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM users AS account
				JOIN roles AS role ON role.id = account.role_id
				WHERE role.code = 'ADMIN'
				""", Integer.class)).isEqualTo(1);

		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email":"%s","password":"Example123"}
						""".formatted(email)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.company.code").value("HARBOURLINE_DEMO"));
	}

	@Test
	void missingInvalidAndReplayedCredentialsCannotCreateAnotherAdministrator() throws Exception {
		mvc.perform(post("/api/setup/administrator").contentType(MediaType.APPLICATION_JSON)
				.content(body(harbourline, "Ada Admin", email, "Example123")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SetupCredentialException.MESSAGE));
		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, "different-setup-token-not-the-real-one")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(harbourline, "Ada Admin", email, "Example123")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SetupCredentialException.MESSAGE));
		assertThat(administratorCount()).isZero();

		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(harbourline, "Ada Admin", email, "Example123")))
				.andExpect(status().isCreated());

		String other = "cdg309-replay-" + UUID.randomUUID() + "@example.com";
		Long straits = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'", Long.class);
		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(straits, "Second Admin", other, "Example123")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(SetupAlreadyCompletedException.MESSAGE));
		mvc.perform(get("/api/setup/status").header(SetupController.CREDENTIAL_HEADER, CREDENTIAL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.initialized").value(true))
				.andExpect(jsonPath("$.organisations.length()").value(0));

		assertThat(administratorCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, other)).isZero();
	}

	@Test
	void anExistingAdministratorClosesSetupWithoutEscalatingTheCaller() throws Exception {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Existing Admin', ?, 'hash', id, ?
				FROM roles WHERE code = 'ADMIN'
				""", "cdg309-existing-" + UUID.randomUUID() + "@example.com", harbourline);
		String forwarder = "cdg309-forwarder-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Forwarder', ?, 'hash', id, ?
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", forwarder, harbourline);

		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(harbourline, "Ada Admin", email, "Example123")))
				.andExpect(status().isConflict());

		assertThat(jdbc.queryForObject("""
				SELECT role.code FROM users AS account
				JOIN roles AS role ON role.id = account.role_id
				WHERE account.email = ?
				""", String.class, forwarder)).isEqualTo("FREIGHT_FORWARDER");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM installation_setup", Integer.class)).isZero();
		assertThat(administratorCount()).isEqualTo(1);
	}

	@Test
	void invalidOrganisationAndPasswordCreateNobody() throws Exception {
		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(999999L, "Ada Admin", email, "Example123")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.organisationId").value("Choose an active organisation."));
		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", harbourline);
		entityManager.clear();
		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(harbourline, "Ada Admin", email, "Example123")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.organisationId").value("Choose an active organisation."));
		jdbc.update("UPDATE companies SET active = TRUE WHERE id = ?", harbourline);
		entityManager.clear();
		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(harbourline, "Ada Admin", email, "short")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").exists());
		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.organisationId").exists())
				.andExpect(jsonPath("$.errors.fullName").exists())
				.andExpect(jsonPath("$.errors.email").exists())
				.andExpect(jsonPath("$.errors.password").exists());
		assertThat(administratorCount()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM installation_setup", Integer.class)).isZero();
	}

	@Test
	void invitationSignupStaysClosedAndTheCredentialIsNotLogged() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);
		try {
			mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"fullName":"Ada Admin","email":"%s","password":"Example123","role":"ADMIN"}
							""".formatted(email)))
					.andExpect(status().isBadRequest());
			mvc.perform(get("/api/setup/status").header(SetupController.CREDENTIAL_HEADER, "not-the-setup-credential"))
					.andExpect(status().isUnauthorized());
		} finally {
			logger.detachAppender(events);
		}
		assertThat(events.list).noneMatch(event -> event.getFormattedMessage().contains(CREDENTIAL)
				|| event.getFormattedMessage().contains("not-the-setup-credential"));
		assertThat(administratorCount()).isZero();
	}

	private int administratorCount() {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM users AS account
				JOIN roles AS role ON role.id = account.role_id
				WHERE role.code = 'ADMIN'
				""", Integer.class);
	}

	private static String body(Long organisationId, String fullName, String email, String password) {
		return """
				{"organisationId":%d,"fullName":"%s","email":"%s","password":"%s"}
				""".formatted(organisationId, fullName, email, password);
	}
}
