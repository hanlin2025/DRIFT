package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.drift.backend.account.exception.IneligibleInvitationException;
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
		jdbc.update("""
				DELETE FROM invitation_revocations
				WHERE invitation_id IN (SELECT id FROM invitations WHERE email LIKE 'cdg310-%')
				   OR operator_user_id IN (SELECT id FROM users WHERE email LIKE 'cdg310-%')
				""");
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
	void anAdministratorCanInviteIntoAnEmptyOrganisationTheyCreated() throws Exception {
		Long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, adminEmail);
		String emptyCode = "CDG313EMPTY" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active, created_by_user_id) VALUES (?, 'Empty Gate Imports', true, ?)",
				emptyCode, adminId);
		Long emptyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, emptyCode);
		String foreignCode = "CDG313OTHER" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, 'Foreign Empty Imports', true)", foreignCode);
		Long foreignId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, foreignCode);

		mvc.perform(get("/api/admin/invitation-organisations").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == '" + emptyCode + "')].name").value("Empty Gate Imports"))
				.andExpect(jsonPath("$[?(@.code == 'HARBOURLINE_DEMO')].name").value("Harbourline Logistics (Demo)"))
				.andExpect(jsonPath("$[?(@.code == '" + foreignCode + "')]").isEmpty())
				.andExpect(jsonPath("$[?(@.code == 'STRAITS_FRESH_DEMO')]").isEmpty());

		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
				.contentType(MediaType.APPLICATION_JSON).content(invite(emptyId, "IMPORTER", guestEmail)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value(guestEmail))
				.andExpect(jsonPath("$.organisation.code").value(emptyCode))
				.andExpect(jsonPath("$.role").value("IMPORTER"));

		mvc.perform(get("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.email == '" + guestEmail + "')].organisation.code").value(emptyCode));

		String occupied = "cdg310-cross-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
				.contentType(MediaType.APPLICATION_JSON).content(invite(straits, "FREIGHT_FORWARDER", occupied)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.organisationId").value("Choose your active organisation."));
		mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
				.contentType(MediaType.APPLICATION_JSON).content(invite(foreignId, "IMPORTER", occupied)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.organisationId").value("Choose your active organisation."));

		jdbc.update("DELETE FROM invitations WHERE email = ?", guestEmail);
		jdbc.update("DELETE FROM companies WHERE id IN (?, ?)", emptyId, foreignId);
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
		Logger logger = (Logger) LoggerFactory.getLogger(AdminInvitationService.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			mvc.perform(post("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail))
					.contentType(MediaType.APPLICATION_JSON).content(invite(harbourline, "FREIGHT_FORWARDER", pending)))
					.andExpect(status().isBadGateway())
					.andExpect(jsonPath("$.message").value(InvitationDeliveryException.MESSAGE));
		} finally {
			logger.detachAppender(logs);
		}
		assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("IllegalStateException"));
		assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("app-password-secret"));
		assertThat(jdbc.queryForObject("SELECT status || ':' || token_hash FROM invitations WHERE email = ?", String.class, pending))
				.isEqualTo("PENDING:" + "a".repeat(64));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invitations WHERE email = ? AND status = 'PENDING'", Integer.class, pending))
				.isEqualTo(1);
	}

	@Test
	void anAdministratorSeesOnlyPendingInvitationsForTheirOrganisation() throws Exception {
		String visibleCode = "visible-invitation-code-should-stay-hidden";
		String expiredCode = "expired-invitation-code-should-stay-hidden";
		String consumedCode = "consumed-invitation-code-should-stay-hidden";
		String foreignCode = "foreign-invitation-code-should-stay-hidden";
		String visible = "cdg310-visible-" + UUID.randomUUID() + "@example.com";
		String expired = "cdg310-expired-" + UUID.randomUUID() + "@example.com";
		String consumed = "cdg310-consumed-" + UUID.randomUUID() + "@example.com";
		String foreign = "cdg310-foreign-" + UUID.randomUUID() + "@example.com";
		insertInvitation(visible, harbourline, "LOGISTICS_MANAGER", hash(visibleCode), "PENDING", "2 days", false);
		insertInvitation(expired, harbourline, "IMPORTER", hash(expiredCode), "PENDING", "-1 hour", false);
		insertInvitation(consumed, harbourline, "FREIGHT_FORWARDER", hash(consumedCode), "CONSUMED", "2 days", true);
		insertInvitation(foreign, straits, "IMPORTER", hash(foreignCode), "PENDING", "2 days", false);

		MvcResult listed = mvc.perform(get("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.email == '" + expired + "')].role").value(org.hamcrest.Matchers.contains("IMPORTER")))
				.andExpect(jsonPath("$[?(@.email == '" + expired + "')].organisation.code").value(org.hamcrest.Matchers.contains("HARBOURLINE_DEMO")))
				.andExpect(jsonPath("$[?(@.email == '" + expired + "')].expiresAt").isNotEmpty())
				.andExpect(jsonPath("$[?(@.email == '" + expired + "')].expired").value(org.hamcrest.Matchers.contains(true)))
				.andExpect(jsonPath("$[?(@.email == '" + visible + "')].role").value(org.hamcrest.Matchers.contains("LOGISTICS_MANAGER")))
				.andExpect(jsonPath("$[?(@.email == '" + visible + "')].organisation.name").value(org.hamcrest.Matchers.contains("Harbourline Logistics (Demo)")))
				.andExpect(jsonPath("$[?(@.email == '" + visible + "')].expired").value(org.hamcrest.Matchers.contains(false)))
				.andExpect(jsonPath("$[?(@.email == '" + consumed + "')]").isEmpty())
				.andExpect(jsonPath("$[?(@.email == '" + foreign + "')]").isEmpty())
				.andReturn();
		String body = listed.getResponse().getContentAsString();
		assertThat(body).doesNotContain(visibleCode, expiredCode, consumedCode, foreignCode);
		assertThat(body).doesNotContain(consumed, foreign);
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE email = ?", String.class, visible)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE email = ?", String.class, expired)).isEqualTo("PENDING");

		String otherAdmin = "cdg310-other-" + UUID.randomUUID() + "@example.com";
		insertUser(otherAdmin, "ADMIN", straits);
		mvc.perform(get("/api/admin/invitations").header("Authorization", "Bearer " + token(otherAdmin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.email == '" + foreign + "')].organisation.code").value(org.hamcrest.Matchers.contains("STRAITS_FRESH_DEMO")))
				.andExpect(jsonPath("$[?(@.email == '" + visible + "')]").isEmpty())
				.andExpect(jsonPath("$[?(@.email == '" + expired + "')]").isEmpty());
	}

	@Test
	void anEmptyInvitationListIsNotAnError() throws Exception {
		String code = "CDG334" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		Long isolated = jdbc.queryForObject("INSERT INTO companies (code, name, active) VALUES (?, 'Isolated Organisation', TRUE) RETURNING id",
				Long.class, code);
		jdbc.update("UPDATE users SET company_id = ? WHERE email = ?", isolated, adminEmail);
		try {
			mvc.perform(get("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(0));
		} finally {
			jdbc.update("UPDATE users SET company_id = ? WHERE email = ?", harbourline, adminEmail);
			jdbc.update("DELETE FROM companies WHERE id = ?", isolated);
		}
	}

	@Test
	void callersWhoCannotSendCannotListInvitations() throws Exception {
		String pending = "cdg310-listed-" + UUID.randomUUID() + "@example.com";
		insertInvitation(pending, harbourline, "IMPORTER", "b".repeat(64), "PENDING", "1 day", false);
		String forwarder = "cdg310-forwarder-" + UUID.randomUUID() + "@example.com";
		insertUser(forwarder, "FREIGHT_FORWARDER", harbourline);
		mvc.perform(get("/api/admin/invitations").header("Authorization", "Bearer " + token(forwarder)))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/invitations"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Your session has ended. Log in again."));
		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", harbourline);
		try {
			mvc.perform(get("/api/admin/invitations").header("Authorization", "Bearer " + token(adminEmail)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value(InvitationManagementForbiddenException.MESSAGE));
		} finally {
			jdbc.update("UPDATE companies SET active = TRUE WHERE id = ?", harbourline);
		}
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE email = ?", String.class, pending)).isEqualTo("PENDING");
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

	@Test
	void anAdministratorWithdrawsAPendingInvitationAndItCannotRegister() throws Exception {
		send(adminEmail, harbourline, "FREIGHT_FORWARDER", guestEmail);
		String code = mailer.lastCode.get();
		Long invitationId = invitationId(guestEmail);
		Long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, adminEmail);

		MvcResult withdrawn = mvc.perform(post("/api/admin/invitations/" + invitationId + "/withdraw")
				.header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(invitationId))
				.andExpect(jsonPath("$.email").value(guestEmail))
				.andExpect(jsonPath("$.status").value("REVOKED"))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andExpect(jsonPath("$.code").doesNotExist())
				.andReturn();
		assertThat(withdrawn.getResponse().getContentAsString()).doesNotContain(code);
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, invitationId)).isEqualTo("REVOKED");
		assertThat(revocations(invitationId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT operator_user_id FROM invitation_revocations WHERE invitation_id = ?", Long.class, invitationId))
				.isEqualTo(adminId);
		assertThat(jdbc.queryForObject("SELECT recorded_at IS NOT NULL FROM invitation_revocations WHERE invitation_id = ?", Boolean.class, invitationId))
				.isTrue();

		mvc.perform(post("/api/invitations/resolve").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + code + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(IneligibleInvitationException.WITHDRAWN));
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(code)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(IneligibleInvitationException.WITHDRAWN));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, guestEmail)).isZero();

		String recordedAt = jdbc.queryForObject("SELECT recorded_at::text FROM invitation_revocations WHERE invitation_id = ?", String.class, invitationId);
		mvc.perform(post("/api/admin/invitations/" + invitationId + "/withdraw").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(InvitationAlreadyWithdrawnException.MESSAGE));
		assertThat(revocations(invitationId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT recorded_at::text FROM invitation_revocations WHERE invitation_id = ?", String.class, invitationId))
				.isEqualTo(recordedAt);
	}

	@Test
	void anAcceptedInvitationCannotBeWithdrawnAndTheAccountStays() throws Exception {
		send(adminEmail, harbourline, "IMPORTER", guestEmail);
		String code = mailer.lastCode.get();
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(code)))
				.andExpect(status().isCreated());
		Long invitationId = invitationId(guestEmail);
		String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, guestEmail);
		mvc.perform(post("/api/admin/invitations/" + invitationId + "/withdraw").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(InvitationAlreadyAcceptedException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, invitationId)).isEqualTo("CONSUMED");
		assertThat(revocations(invitationId)).isZero();
		assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, guestEmail)).isEqualTo(passwordHash);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, guestEmail)).isEqualTo(1);
	}

	@Test
	void anExpiredInvitationStaysUnusableWhenWithdrawIsRefused() throws Exception {
		String code = "expired-withdraw-code-should-stay-hidden";
		insertInvitation(guestEmail, harbourline, "LOGISTICS_MANAGER", hash(code), "PENDING", "-1 hour", false);
		Long invitationId = invitationId(guestEmail);
		String expiry = jdbc.queryForObject("SELECT expires_at::text FROM invitations WHERE id = ?", String.class, invitationId);
		mvc.perform(post("/api/admin/invitations/" + invitationId + "/withdraw").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(InvitationExpiredException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, invitationId)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT expires_at::text FROM invitations WHERE id = ?", String.class, invitationId)).isEqualTo(expiry);
		assertThat(revocations(invitationId)).isZero();
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(code)))
				.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, guestEmail)).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, invitationId)).isEqualTo("PENDING");
	}

	@Test
	void missingAndInaccessibleInvitationsAreNotRevealed() throws Exception {
		String foreign = "cdg310-hidden-" + UUID.randomUUID() + "@example.com";
		insertInvitation(foreign, straits, "IMPORTER", "c".repeat(64), "PENDING", "1 day", false);
		Long foreignId = invitationId(foreign);
		String forwarder = "cdg310-forwarder-" + UUID.randomUUID() + "@example.com";
		insertUser(forwarder, "FREIGHT_FORWARDER", harbourline);

		MvcResult hidden = mvc.perform(post("/api/admin/invitations/" + foreignId + "/withdraw").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(InvitationAccessDeniedException.MESSAGE))
				.andExpect(jsonPath("$.email").doesNotExist())
				.andReturn();
		assertThat(hidden.getResponse().getContentAsString()).doesNotContain(foreign);
		mvc.perform(post("/api/admin/invitations/999999999/withdraw").header("Authorization", "Bearer " + token(adminEmail)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(InvitationAccessDeniedException.MESSAGE));
		mvc.perform(post("/api/admin/invitations/" + foreignId + "/withdraw").header("Authorization", "Bearer " + token(forwarder)))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/admin/invitations/" + foreignId + "/withdraw"))
				.andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, foreignId)).isEqualTo("PENDING");
		assertThat(revocations(foreignId)).isZero();
	}

	@Test
	void replacingAnInvitationDoesNotRecordAWithdrawal() throws Exception {
		send(adminEmail, harbourline, "FREIGHT_FORWARDER", guestEmail);
		Long first = invitationId(guestEmail);
		send(adminEmail, harbourline, "IMPORTER", guestEmail);
		assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, first)).isEqualTo("REVOKED");
		assertThat(revocations(first)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invitations WHERE email = ? AND status = 'PENDING'", Integer.class, guestEmail))
				.isEqualTo(1);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void withdrawalAndAcceptanceCannotBothSucceed() throws Exception {
		send(adminEmail, harbourline, "FREIGHT_FORWARDER", guestEmail);
		String code = mailer.lastCode.get();
		Long invitationId = invitationId(guestEmail);
		String bearer = token(adminEmail);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var withdrawn = executor.submit(() -> {
				start.await();
				return mvc.perform(post("/api/admin/invitations/" + invitationId + "/withdraw")
						.header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus();
			});
			var registered = executor.submit(() -> {
				start.await();
				return mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration(code)))
						.andReturn().getResponse().getStatus();
			});
			start.countDown();
			int withdrawStatus = withdrawn.get(20, TimeUnit.SECONDS);
			int registerStatus = registered.get(20, TimeUnit.SECONDS);
			Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, guestEmail);
			String status = jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, invitationId);
			int recorded = revocations(invitationId);
			if (registerStatus == 201) {
				assertThat(withdrawStatus).isEqualTo(409);
				assertThat(accounts).isEqualTo(1);
				assertThat(status).isEqualTo("CONSUMED");
				assertThat(recorded).isZero();
			} else {
				assertThat(List.of(registerStatus, withdrawStatus)).containsExactly(400, 200);
				assertThat(accounts).isZero();
				assertThat(status).isEqualTo("REVOKED");
				assertThat(recorded).isEqualTo(1);
			}
		}
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

	private void insertInvitation(String email, Long companyId, String role, String tokenHash, String status, String expiry,
			boolean consumed) {
		if (consumed) {
			jdbc.update("""
					INSERT INTO invitations (email, company_id, role, token_hash, expires_at, status, consumed_at)
					VALUES (?, ?, ?, ?, NOW() + CAST(? AS interval), 'CONSUMED', NOW())
					""", email, companyId, role, tokenHash, expiry);
			return;
		}
		jdbc.update("""
				INSERT INTO invitations (email, company_id, role, token_hash, expires_at, status)
				VALUES (?, ?, ?, ?, NOW() + CAST(? AS interval), ?)
				""", email, companyId, role, tokenHash, expiry, status);
	}

	private Long invitationId(String email) {
		return jdbc.queryForObject("SELECT id FROM invitations WHERE email = ? ORDER BY id DESC LIMIT 1", Long.class, email);
	}

	private int revocations(Long invitationId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM invitation_revocations WHERE invitation_id = ?", Integer.class, invitationId);
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
				throw new IllegalStateException("smtp failed for app-password-secret");
			}
			lastCode.set(code);
			assertThat(link).contains("#invite=" + code);
			assertThat(link).doesNotContain("token=");
		}
	}
}
