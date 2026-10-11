package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.drift.backend.account.admin.exception.AdminAssignmentConflictException;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.admin.exception.InvalidAssignmentException;
import com.drift.backend.account.exception.SessionEndedException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminAssignmentIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private String adminToken;
	private Long harbourlineId;
	private Long straitsId;

	@BeforeEach
	void administrator() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		straitsId = companyId("STRAITS_FRESH_DEMO");
		adminToken = tokenFor(createUser("Admin User", "ADMIN", harbourlineId));
	}

	@Test
	void assignsAnOrganisationAndRoleAndEndsThePreviousSession() throws Exception {
		String email = createUser("Alice Tan", "FREIGHT_FORWARDER", harbourlineId);
		String previousToken = tokenFor(email);
		Long userId = userId(email);

		assign(adminToken, userId, straitsId, "LOGISTICS_MANAGER")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(AssignmentResponse.MESSAGE))
				.andExpect(jsonPath("$.user.email").value(email))
				.andExpect(jsonPath("$.user.role").value("LOGISTICS_MANAGER"))
				.andExpect(jsonPath("$.user.organisation.code").value("STRAITS_FRESH_DEMO"));

		assertThat(jdbc.queryForObject("SELECT code FROM roles JOIN users ON users.role_id = roles.id WHERE users.id = ?",
				String.class, userId)).isEqualTo("LOGISTICS_MANAGER");
		assertThat(jdbc.queryForObject("SELECT company_id FROM users WHERE id = ?", Long.class, userId)).isEqualTo(straitsId);
		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId)).isEqualTo(1L);

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + tokenFor(email)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("LOGISTICS_MANAGER"))
				.andExpect(jsonPath("$.company.code").value("STRAITS_FRESH_DEMO"));
	}

	@Test
	void keepsTheSessionWhenTheAssignmentDoesNotChange() throws Exception {
		String email = createUser("Same Assignment", "IMPORTER", straitsId);
		String previousToken = tokenFor(email);

		assign(adminToken, userId(email), straitsId, "IMPORTER")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(AssignmentResponse.MESSAGE));

		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE email = ?", Long.class, email)).isZero();
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsAMissingInactiveOrUnknownOrganisationAndAnUnknownRole() throws Exception {
		String email = createUser("Pat Lim", "FREIGHT_FORWARDER", harbourlineId);
		Long userId = userId(email);
		Long inactiveId = inactiveCompany();

		assign(adminToken, userId, inactiveId, "IMPORTER")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidAssignmentException.MESSAGE));
		assign(adminToken, userId, 999999L, "IMPORTER")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidAssignmentException.MESSAGE));
		assign(adminToken, userId, straitsId, "PLANNER")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidAssignmentException.MESSAGE));
		mvc.perform(patch("/api/admin/users/" + userId + "/assign").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"organisationId\":null,\"role\":\"IMPORTER\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidAssignmentException.MESSAGE));

		assertThat(jdbc.queryForObject("SELECT company_id FROM users WHERE id = ?", Long.class, userId)).isEqualTo(harbourlineId);
	}

	@Test
	void reportsAnUnknownUserAndANonNumericIdAsNotFound() throws Exception {
		assign(adminToken, 999999L, straitsId, "IMPORTER")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));
		mvc.perform(patch("/api/admin/users/not-a-number/assign").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"organisationId\":" + straitsId + ",\"role\":\"IMPORTER\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));
	}

	@Test
	void rejectsAssignmentByANonAdministratorAndWithoutASession() throws Exception {
		String email = createUser("Importer User", "IMPORTER", straitsId);
		Long targetId = userId(createUser("Target User", "FREIGHT_FORWARDER", harbourlineId));

		assign(tokenFor(email), targetId, straitsId, "IMPORTER")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		mvc.perform(patch("/api/admin/users/" + targetId + "/assign").contentType(MediaType.APPLICATION_JSON)
				.content("{\"organisationId\":" + straitsId + ",\"role\":\"IMPORTER\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(patch("/api/admin/users/" + targetId + "/assign").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(AdminAssignmentExceptionHandler.UNREADABLE));
	}

	@Test
	void rejectsASecondAdministratorForTheSameOrganisation() throws Exception {
		String email = createUser("Second Admin", "FREIGHT_FORWARDER", harbourlineId);

		assign(adminToken, userId(email), harbourlineId, "ADMIN")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(AdminAssignmentConflictException.MESSAGE));
	}

	@Test
	void listsUsersRolesAndActiveOrganisationsForAnAdministratorOnly() throws Exception {
		String email = createUser("Listed User", "IMPORTER", straitsId);
		inactiveCompany();
		String importerToken = tokenFor(createUser("Blocked Importer", "IMPORTER", harbourlineId));

		mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.email == '" + email + "')].role").value("IMPORTER"))
				.andExpect(jsonPath("$[?(@.email == '" + email + "')].organisation.name").value("Straits Fresh Imports (Demo)"));
		mvc.perform(get("/api/admin/roles").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'LOGISTICS_MANAGER')].label").value("Logistics manager"))
				.andExpect(jsonPath("$.length()").value(4));
		mvc.perform(get("/api/admin/organisations").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'CLOSED_CO') ]").isEmpty())
				.andExpect(jsonPath("$[?(@.code == 'HARBOURLINE_DEMO')].name").value("Harbourline Logistics (Demo)"));

		mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		mvc.perform(get("/api/admin/roles").header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/organisations"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void documentsTheAssignmentApi() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/admin/users'].get.summary").value("List users for assignment"))
				.andExpect(jsonPath("$.paths['/api/admin/users'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/admin/roles'].get.summary").value("List roles for assignment"))
				.andExpect(jsonPath("$.paths['/api/admin/organisations'].get.summary").value("List organisations for assignment"))
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.summary")
						.value("Assign a user to an organisation and role"))
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.responses['400'].content['application/json'].example.message")
						.value(InvalidAssignmentException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.responses['403'].content['application/json'].example.message")
						.value(AssignmentForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.responses['404'].content['application/json'].example.message")
						.value(AssignedUserNotFoundException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.responses['409'].content['application/json'].example.message")
						.value(AdminAssignmentConflictException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/admin/users/{userId}/assign'].patch.responses['200'].content['application/json'].schema.$ref")
						.value("#/components/schemas/Assignment"));
	}

	private org.springframework.test.web.servlet.ResultActions assign(String token, Long userId, Long organisationId, String role)
			throws Exception {
		return mvc.perform(patch("/api/admin/users/" + userId + "/assign").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"organisationId\":" + organisationId + ",\"role\":\"" + role + "\"}"));
	}

	private String createUser(String name, String role, Long company) {
		String email = "cdg108-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ?
				FROM roles WHERE code = ?
				""", name, email, passwords.encode("Example123"), company, role);
		entityManager.clear();
		return email;
	}

	private Long inactiveCompany() {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES ('CLOSED_CO', 'Closed Company', false)");
		return companyId("CLOSED_CO");
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
