package com.drift.backend.account.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.setup.token=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SetupClosedIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	@Test
	void setupWithoutAnOperatorCredentialCannotCreateAnAdministrator() throws Exception {
		Long harbourline = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		mvc.perform(get("/api/setup/status").header(SetupController.CREDENTIAL_HEADER, "test-only-setup-token-not-used-outside-tests"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(SetupUnavailableException.MESSAGE));
		mvc.perform(post("/api/setup/administrator")
				.header(SetupController.CREDENTIAL_HEADER, "test-only-setup-token-not-used-outside-tests")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"organisationId":%d,"fullName":"Ada Admin","email":"closed@example.com","password":"Example123"}
						""".formatted(harbourline)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(SetupUnavailableException.MESSAGE));
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM users AS account
				JOIN roles AS role ON role.id = account.role_id
				WHERE role.code = 'ADMIN'
				""", Integer.class)).isZero();
	}
}
