package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountRemovalAuditIntegrationTests {

	private static final String[] NON_ADMIN_ROLES = { "IMPORTER", "FREIGHT_FORWARDER", "LOGISTICS_MANAGER" };

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private String adminEmail;
	private String adminToken;
	private Long harbourlineId;
	private Long straitsId;
	private int baselineAudits;

	@BeforeEach
	void administrator() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		straitsId = companyId("STRAITS_FRESH_DEMO");
		adminEmail = createUser("Admin User", "ADMIN", harbourlineId);
		adminToken = tokenFor(adminEmail);
		baselineAudits = totalAudits();
	}

	@Test
	void recordsOneEntryForASuccessfulRemoval() throws Exception {
		Long adminId = userId(adminEmail);
		String email = createUser("Alice Tan", "FREIGHT_FORWARDER", harbourlineId);
		Long userId = userId(email);

		Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
		remove(adminToken, userId).andExpect(status().isOk());
		Instant after = Instant.now();

		assertThat(auditCount()).isEqualTo(1);
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM account_status_audits WHERE target_user_id = ?", userId);
		assertThat(row.get("operator_user_id")).isEqualTo(adminId);
		assertThat(row.get("organisation_id")).isEqualTo(harbourlineId);
		assertThat(row.get("previous_status")).isEqualTo("ACTIVE");
		assertThat(row.get("new_status")).isEqualTo("REMOVED");
		Instant recordedAt = ((Timestamp) row.get("recorded_at")).toInstant();
		assertThat(recordedAt).isBetween(before, after);

		mvc.perform(get("/api/admin/account-status-audits").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].operator.id").value(adminId))
				.andExpect(jsonPath("$[0].operator.email").value(adminEmail))
				.andExpect(jsonPath("$[0].target.id").value(userId))
				.andExpect(jsonPath("$[0].target.email").value(email))
				.andExpect(jsonPath("$[0].organisationId").value(harbourlineId))
				.andExpect(jsonPath("$[0].previousStatus").value("ACTIVE"))
				.andExpect(jsonPath("$[0].newStatus").value("REMOVED"))
				.andExpect(jsonPath("$[0].recordedAt").exists());
	}

	@Test
	void writesNoEntryForARejectedRemoval() throws Exception {
		remove(adminToken, userId(adminEmail)).andExpect(status().isBadRequest());
		assertThat(auditCount()).isZero();

		jdbc.update("INSERT INTO companies (code, name, active) VALUES ('CLOSED_CO', 'Closed Company', false)");
		Long closedId = companyId("CLOSED_CO");
		String closedAdminToken = tokenFor(createUser("Closed Admin", "ADMIN", closedId));
		remove(closedAdminToken, userId(createUser("Closed User", "IMPORTER", closedId))).andExpect(status().isBadRequest());
		assertThat(auditCount()).isZero();

		Long target = userId(createUser("Target User", "IMPORTER", harbourlineId));
		for (String role : NON_ADMIN_ROLES) {
			remove(tokenFor(createUser("Not Admin " + role, role, harbourlineId)), target).andExpect(status().isForbidden());
		}
		assertThat(auditCount()).isZero();

		remove(adminToken, 999999999L).andExpect(status().isNotFound());
		remove(adminToken, userId(createUser("Other Company", "IMPORTER", straitsId))).andExpect(status().isNotFound());
		assertThat(auditCount()).isZero();

		remove(adminToken, target).andExpect(status().isOk());
		remove(adminToken, target).andExpect(status().isConflict());
		assertThat(auditCount()).isEqualTo(1);
	}

	@Test
	void listsOnlyTheAdministratorsOwnCompanyNewestFirst() throws Exception {
		Long otherOperator = userId(createUser("Other Operator", "IMPORTER", straitsId));
		Long otherTarget = userId(createUser("Other Target", "IMPORTER", straitsId));
		jdbc.update("""
				INSERT INTO account_status_audits
				    (operator_user_id, target_user_id, organisation_id, previous_status, new_status, recorded_at)
				VALUES (?, ?, ?, 'ACTIVE', 'REMOVED', now())
				""", otherOperator, otherTarget, straitsId);
		Long first = userId(createUser("First Removed", "IMPORTER", harbourlineId));
		Long second = userId(createUser("Second Removed", "IMPORTER", harbourlineId));

		remove(adminToken, first).andExpect(status().isOk());
		remove(adminToken, second).andExpect(status().isOk());

		mvc.perform(get("/api/admin/account-status-audits").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].target.id").value(second))
				.andExpect(jsonPath("$[1].target.id").value(first))
				.andExpect(jsonPath("$[?(@.organisationId == " + straitsId + ")]").isEmpty());
	}

	@Test
	void forbidsRolesOtherThanAdministratorAndRequiresASession() throws Exception {
		for (String role : NON_ADMIN_ROLES) {
			String token = tokenFor(createUser("Not Admin " + role, role, harbourlineId));
			mvc.perform(get("/api/admin/account-status-audits").header("Authorization", "Bearer " + token))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		}
		mvc.perform(get("/api/admin/account-status-audits"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void documentsTheAuditApi() throws Exception {
		String path = "$.paths['/api/admin/account-status-audits'].get";
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(path + ".summary").value("List account status changes"))
				.andExpect(jsonPath(path + ".security[0].bearerAuth").exists())
				.andExpect(jsonPath(path + ".responses['200'].content['application/json'].schema.items.$ref")
						.value("#/components/schemas/AccountStatusAudit"))
				.andExpect(jsonPath(path + ".responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['403'].content['application/json'].example.message")
						.value(AssignmentForbiddenException.MESSAGE));
	}

	private int auditCount() {
		return totalAudits() - baselineAudits;
	}

	private int totalAudits() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM account_status_audits", Integer.class);
	}

	private org.springframework.test.web.servlet.ResultActions remove(String token, Long userId) throws Exception {
		return mvc.perform(patch("/api/admin/users/" + userId + "/remove").header("Authorization", "Bearer " + token));
	}

	private String createUser(String name, String role, Long company) {
		String email = "cdg157-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ?
				FROM roles WHERE code = ?
				""", name, email, passwords.encode("Example123"), company, role);
		entityManager.clear();
		return email;
	}

	private String tokenFor(String email) throws Exception {
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	private Long userId(String email) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
}
