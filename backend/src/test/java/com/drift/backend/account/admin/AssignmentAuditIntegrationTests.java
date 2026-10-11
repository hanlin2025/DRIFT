package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class AssignmentAuditIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private String adminEmail;
	private String adminToken;
	private Long harbourlineId;
	private Long straitsId;

	@BeforeEach
	void administrator() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		straitsId = companyId("STRAITS_FRESH_DEMO");
		adminEmail = createUser("Admin User", "ADMIN", harbourlineId);
		adminToken = tokenFor(adminEmail);
	}

	@Test
	void recordsBeforeAndAfterValuesAndSkipsUnchangedOrRejectedAssignments() throws Exception {
		String email = createUser("Alice Tan", "FREIGHT_FORWARDER", harbourlineId);
		Long userId = userId(email);

		assign(userId, straitsId, "LOGISTICS_MANAGER").andExpect(status().isOk());

		mvc.perform(get("/api/admin/assignment-audits").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].operator.email").value(adminEmail))
				.andExpect(jsonPath("$[0].operator.fullName").value("Admin User"))
				.andExpect(jsonPath("$[0].target.email").value(email))
				.andExpect(jsonPath("$[0].target.fullName").value("Alice Tan"))
				.andExpect(jsonPath("$[0].previousRole").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$[0].assignedRole").value("LOGISTICS_MANAGER"))
				.andExpect(jsonPath("$[0].previousOrganisation.code").value("HARBOURLINE_DEMO"))
				.andExpect(jsonPath("$[0].previousOrganisation.name").value("Harbourline Logistics (Demo)"))
				.andExpect(jsonPath("$[0].organisation.code").value("STRAITS_FRESH_DEMO"))
				.andExpect(jsonPath("$[0].organisation.name").value("Straits Fresh Imports (Demo)"))
				.andExpect(jsonPath("$[0].recordedAt").isNotEmpty());

		assign(userId, straitsId, "LOGISTICS_MANAGER").andExpect(status().isOk());
		assign(userId, straitsId, "PLANNER").andExpect(status().isBadRequest());
		assertThat(count()).isEqualTo(1);

		assign(userId, straitsId, "IMPORTER").andExpect(status().isOk());
		mvc.perform(get("/api/admin/assignment-audits").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].assignedRole").value("IMPORTER"))
				.andExpect(jsonPath("$[0].previousRole").value("LOGISTICS_MANAGER"))
				.andExpect(jsonPath("$[1].assignedRole").value("LOGISTICS_MANAGER"));
	}

	@Test
	void recordsAnAssignmentForAUserWhoHadNoOrganisation() throws Exception {
		String email = createUser("No Company", "FREIGHT_FORWARDER", null);

		assign(userId(email), straitsId, "IMPORTER").andExpect(status().isOk());

		mvc.perform(get("/api/admin/assignment-audits").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].target.email").value(email))
				.andExpect(jsonPath("$[0].previousOrganisation").value(nullValue()))
				.andExpect(jsonPath("$[0].organisation.id").value(straitsId));
	}

	@Test
	void hidesTheAuditFromOtherRolesAndFromAMissingSession() throws Exception {
		String importer = createUser("Importer User", "IMPORTER", straitsId);

		mvc.perform(get("/api/admin/assignment-audits").header("Authorization", "Bearer " + tokenFor(importer)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		mvc.perform(get("/api/admin/assignment-audits"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void documentsTheAssignmentAudit() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/admin/assignment-audits'].get.summary").value("List assignment changes"))
				.andExpect(jsonPath("$.paths['/api/admin/assignment-audits'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/admin/assignment-audits'].get.responses['200'].content['application/json'].schema.items.$ref")
						.value("#/components/schemas/AssignmentAudit"))
				.andExpect(jsonPath("$.paths['/api/admin/assignment-audits'].get.responses['403'].content['application/json'].example.message")
						.value(AssignmentForbiddenException.MESSAGE));
	}

	private org.springframework.test.web.servlet.ResultActions assign(Long userId, Long organisationId, String role) throws Exception {
		return mvc.perform(patch("/api/admin/users/" + userId + "/assign").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"organisationId\":" + organisationId + ",\"role\":\"" + role + "\"}"));
	}

	private String createUser(String name, String role, Long company) {
		String email = "cdg111-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ?
				FROM roles WHERE code = ?
				""", name, email, passwords.encode("Example123"), company, role);
		entityManager.clear();
		return email;
	}

	private int count() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM user_assignment_audits", Integer.class);
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
