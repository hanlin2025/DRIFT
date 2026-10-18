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

import com.drift.backend.account.admin.exception.AccountAlreadyRemovedException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.exception.SessionEndedException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountRemovalIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private String adminToken;
	private Long harbourlineId;

	@BeforeEach
	void administrator() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		adminToken = tokenFor(createUser("Admin User", "ADMIN", harbourlineId));
	}

	@Test
	void marksTheAccountRemovedAndKeepsTheRow() throws Exception {
		String email = createUser("Alice Tan", "FREIGHT_FORWARDER", harbourlineId);
		Long userId = userId(email);

		remove(adminToken, userId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(userId))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.status").value("REMOVED"));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId)).isEqualTo("REMOVED");
		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId)).isEqualTo(1L);
	}

	@Test
	void endsTheRemovedUsersExistingSession() throws Exception {
		String email = createUser("Pat Lim", "IMPORTER", harbourlineId);
		String previousToken = tokenFor(email);
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isOk());

		remove(adminToken, userId(email)).andExpect(status().isOk());

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void rejectsRemovingAnAlreadyRemovedAccount() throws Exception {
		Long userId = userId(createUser("Twice Removed", "IMPORTER", harbourlineId));

		remove(adminToken, userId).andExpect(status().isOk());
		remove(adminToken, userId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(AccountAlreadyRemovedException.MESSAGE));

		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId)).isEqualTo(1L);
	}

	@Test
	void reportsAnUnknownUserAsNotFound() throws Exception {
		remove(adminToken, 999999999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));
	}

	@Test
	void listsTheAccountStatus() throws Exception {
		String email = createUser("Listed User", "IMPORTER", harbourlineId);

		mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.email == '" + email + "')].status").value("ACTIVE"));
	}

	@Test
	void documentsTheRemovalApi() throws Exception {
		String path = "$.paths['/api/admin/users/{userId}/remove'].patch";
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(path + ".summary").value("Remove a user account"))
				.andExpect(jsonPath(path + ".security[0].bearerAuth").exists())
				.andExpect(jsonPath(path + ".responses['200'].content['application/json'].schema.$ref")
						.value("#/components/schemas/AdminUser"))
				.andExpect(jsonPath(path + ".responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['404'].content['application/json'].example.message")
						.value(AssignedUserNotFoundException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['409'].content['application/json'].example.message")
						.value(AccountAlreadyRemovedException.MESSAGE));
	}

	private org.springframework.test.web.servlet.ResultActions remove(String token, Long userId) throws Exception {
		return mvc.perform(patch("/api/admin/users/" + userId + "/remove").header("Authorization", "Bearer " + token));
	}

	private String createUser(String name, String role, Long company) {
		String email = "cdg155-" + UUID.randomUUID() + "@example.com";
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
