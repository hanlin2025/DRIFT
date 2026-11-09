package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import com.jayway.jsonpath.JsonPath;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminInvitationIntegrationTests.CapturingMail.class)
class AdminInvitationIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired CapturingInvitationMailer mailer;

	private Long harbourline;
	private Long straits;
	private String adminEmail;
	private String guestEmail;

	@BeforeEach
	void accounts() {
		mailer.failNext = false;
		mailer.lastCode.set(null);
		harbourline = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		straits = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'", Long.class);
		adminEmail = "cdg310-admin-" + UUID.randomUUID() + "@example.com";
		guestEmail = "cdg310-guest-" + UUID.randomUUID() + "@example.com";
		insertUser(adminEmail, "ADMIN", harbourline);
	}

	@AfterEach
	void removeRows() {
		jdbc.update("DELETE FROM users WHERE email LIKE 'cdg310-%'");
		jdbc.update("DELETE FROM invitations WHERE email LIKE 'cdg310-%'");
	}

	@Test
	void anAdministratorSendsAnInvitationTheRecipientCanUseOnce() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);
		String body;
		try {
			MvcResult sent = mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
					.contentType(MediaType.APPLICATION_JSON).content(invite(harbourline, "FREIGHT_FORWARDER", guestEmail)))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.email").value(guestEmail))
					.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
					.andExpect(jsonPath("$.organisation.code").value("HARBOURLINE_DEMO"))
					.andExpect(jsonPath("$.expiresAt").isNotEmpty())
					.andExpect(jsonPath("$.token").doesNotExist())
					.andExpect(jsonPath("$.code").doesNotExist())
					.andReturn();
			body = sent.getResponse().getContentAsString();
		} finally {
			logger.detachAppender(events);
		}
		String code = mailer.lastCode.get();
		assertThat(body).doesNotContain(code);
		assertThat(events.list).noneMatch(event -> event.getFormattedMessage().contains(code));
		assertThat(jdbc.queryForObject("SELECT token_hash = ? FROM invitations WHERE email = ? AND status = 'PENDING'",
				Boolean.class, hash(code), guestEmail)).isTrue();

		mvc.perform(post("/api/invitations/resolve").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + code + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(guestEmail))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"));
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(code)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.email").value(guestEmail));
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + guestEmail + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.company.code").value("HARBOURLINE_DEMO"));
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(code)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("This invitation is invalid, expired, or no longer available. Ask for a new invitation."));
	}

	@Test
	void replacedExpiredAndForeignInvitationsCannotRegister() throws Exception {
		send(adminEmail, harbourline, "LOGISTICS_MANAGER", guestEmail);
		String first = mailer.lastCode.get();
		send(adminEmail, harbourline, "IMPORTER", guestEmail);
		String second = mailer.lastCode.get();
		assertThat(first).isNotEqualTo(second);
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(first)))
				.andExpect(status().isBadRequest());
		jdbc.update("UPDATE invitations SET expires_at = NOW() - INTERVAL '1 minute' WHERE email = ? AND status = 'PENDING'", guestEmail);
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(second)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("This invitation is invalid, expired, or no longer available. Ask for a new invitation."));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, guestEmail)).isZero();

		String otherAdmin = "cdg310-other-" + UUID.randomUUID() + "@example.com";
		insertUser(otherAdmin, "ADMIN", straits);
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
				.contentType(MediaType.APPLICATION_JSON).content(invite(straits, "FREIGHT_FORWARDER", "cdg310-cross-" + UUID.randomUUID() + "@example.com")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.organisationId").value("Choose your active organisation."));
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
				.contentType(MediaType.APPLICATION_JSON).content(invite(harbourline, "ADMIN", guestEmail)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.role").value("Choose a role this administrator can invite."));
	}

	@Test
	void unauthorizedCallersAndMailFailuresDoNotIssueAnInvitation() throws Exception {
		String forwarder = "cdg310-forwarder-" + UUID.randomUUID() + "@example.com";
		insertUser(forwarder, "FREIGHT_FORWARDER", harbourline);
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(forwarder))
				.contentType(MediaType.APPLICATION_JSON).content(invite(harbourline, "IMPORTER", guestEmail)))
				.andExpect(status().isForbidden());
		insertUser(guestEmail, "IMPORTER", straits);
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
				.contentType(MediaType.APPLICATION_JSON).content(invite(harbourline, "IMPORTER", guestEmail)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("An account with this email already exists"));

		String pending = "cdg310-pending-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
				VALUES (?, ?, 'FREIGHT_FORWARDER', ?, NOW() + INTERVAL '1 day')
				""", pending, harbourline, "a".repeat(64));
		mailer.failNext = true;
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
				.contentType(MediaType.APPLICATION_JSON).content(invite(harbourline, "FREIGHT_FORWARDER", pending)))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.message").value(InvitationDeliveryException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT status || ':' || token_hash FROM invitations WHERE email = ?", String.class, pending))
				.isEqualTo("PENDING:" + "a".repeat(64));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invitations WHERE email = ? AND status = 'PENDING'", Integer.class, pending))
				.isEqualTo(1);
	}

	@Test
	void anInactiveOrganisationCannotSend() throws Exception {
		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", harbourline);
		try {
			mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
					.contentType(MediaType.APPLICATION_JSON).content(invite(harbourline, "IMPORTER", guestEmail)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.message").value(NoEligibleOrganisationException.MESSAGE));
		} finally {
			jdbc.update("UPDATE companies SET active = TRUE WHERE id = ?", harbourline);
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invitations WHERE email = ?", Integer.class, guestEmail)).isZero();
	}

	private void send(String admin, Long organisationId, String role, String email) throws Exception {
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(admin))
				.contentType(MediaType.APPLICATION_JSON).content(invite(organisationId, role, email)))
				.andExpect(status().isCreated());
	}

	private String token(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private void insertUser(String email, String role, Long companyId) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Test User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
	}

	private static String invite(Long organisationId, String role, String email) {
		return """
				{"organisationId":%d,"role":"%s","email":"%s"}
				""".formatted(organisationId, role, email);
	}

	private String registration(String code) {
		return """
				{"fullName":"Guest User","email":"%s","password":"Example123","invitationToken":"%s"}
				""".formatted(guestEmail, code);
	}

	private static String hash(String code) {
		return com.drift.backend.account.registration.invitation.InvitationToken.hash(code);
	}

	@TestConfiguration
	static class CapturingMail {
		@Bean
		@Primary
		CapturingInvitationMailer invitationMailer() {
			return new CapturingInvitationMailer();
		}
	}

	static class CapturingInvitationMailer implements InvitationMailer {
		volatile boolean failNext;
		final AtomicReference<String> lastCode = new AtomicReference<>();

		@Override
		public void sendInvitation(String email, String link, String code, String organisationName, String roleLabel, Duration validFor) {
			if (failNext) {
				failNext = false;
				throw new IllegalStateException("mailpit unavailable");
			}
			lastCode.set(code);
			assertThat(link).contains("#invite=" + code);
			assertThat(link).doesNotContain("token=");
		}
	}
}
