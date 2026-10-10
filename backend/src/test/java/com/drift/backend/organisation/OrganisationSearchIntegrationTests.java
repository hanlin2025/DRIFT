package com.drift.backend.organisation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.organisation.exception.InvalidOrganisationQueryException;
import com.drift.backend.organisation.exception.OrganisationAccessForbiddenException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganisationSearchIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private Long harbourlineId;
	private String forwarderToken;
	private String importerToken;

	@BeforeEach
	void accounts() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		forwarderToken = account("cdg120-ff-", harbourlineId, "FREIGHT_FORWARDER");
		importerToken = account("cdg120-im-", harbourlineId, "IMPORTER");
	}

	@Test
	void returnsOtherActiveCompaniesAndMatchesTheNameLiterally() throws Exception {
		Long percentId = insertCompany("PERCENT_" + UUID.randomUUID(), "100% Cold Chain");
		insertCompany("IDLE_" + UUID.randomUUID(), "Idle Imports", false);

		mvc.perform(get("/api/organisations").param("type", "importer")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(OrganisationService.DEFAULT_PAGE_SIZE))
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items", hasSize(2)))
				.andExpect(jsonPath("$.items[0].name").value("100% Cold Chain"))
				.andExpect(jsonPath("$.items[0].id").value(percentId))
				.andExpect(jsonPath("$.items[1].code").value("STRAITS_FRESH_DEMO"))
				.andExpect(jsonPath("$.items[1].name").value("Straits Fresh Imports (Demo)"))
				.andExpect(jsonPath("$.items[?(@.code == 'HARBOURLINE_DEMO')]").isEmpty())
				.andExpect(jsonPath("$.items[?(@.name == 'Idle Imports')]").isEmpty());

		mvc.perform(get("/api/organisations").param("type", "IMPORTER").param("name", "  fresh  ")
				.header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].name").value("Straits Fresh Imports (Demo)"));

		mvc.perform(get("/api/organisations").param("type", "importer").param("name", "%")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].name").value("100% Cold Chain"));

		mvc.perform(get("/api/organisations").param("type", "importer").param("name", "harbourline")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(0))
				.andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	void pagesTheMatchingOrganisations() throws Exception {
		insertCompany("BETA_" + UUID.randomUUID(), "Beta Imports");
		insertCompany("GAMMA_" + UUID.randomUUID(), "Gamma Imports");

		mvc.perform(get("/api/organisations").param("type", "importer").param("name", "imports")
				.param("page", "1").param("size", "1")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].name").value("Gamma Imports"));

		mvc.perform(get("/api/organisations").param("type", "importer").param("page", "9").param("size", "20")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(0)))
				.andExpect(jsonPath("$.total").value(3));
	}

	@Test
	void rejectsAMissingOrUnsupportedTypeAndAnInvalidPage() throws Exception {
		mvc.perform(get("/api/organisations").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.TYPE));
		mvc.perform(get("/api/organisations").param("type", "exporter")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.TYPE));
		mvc.perform(get("/api/organisations").param("type", "importer").param("page", "-1")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.PAGE));
		mvc.perform(get("/api/organisations").param("type", "importer").param("size", "0")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.SIZE));
		mvc.perform(get("/api/organisations").param("type", "importer").param("size", "101")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.SIZE));
		mvc.perform(get("/api/organisations").param("type", "importer").param("page", "next")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.PAGE));
		mvc.perform(get("/api/organisations?type=importer&page=")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.PAGE));
		mvc.perform(get("/api/organisations?type=importer&size=")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationQueryException.SIZE));
	}

	@Test
	void requiresASessionAndAnActiveCompany() throws Exception {
		mvc.perform(get("/api/organisations").param("type", "importer"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));

		String unassigned = "cdg120-none-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'No Company', ?, ?, id
				FROM roles WHERE code = 'IMPORTER'
				""", unassigned, passwords.encode("Example123"));
		mvc.perform(get("/api/organisations").param("type", "importer")
				.header("Authorization", "Bearer " + tokenFor(unassigned)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(OrganisationAccessForbiddenException.MESSAGE));

		jdbc.update("UPDATE organisations SET active = FALSE WHERE id = ?", harbourlineId);
		entityManager.clear();
		mvc.perform(get("/api/organisations").param("type", "importer")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(OrganisationAccessForbiddenException.MESSAGE));
	}

	@Test
	void documentsTheSearchInSwagger() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/organisations'].get.summary").value("Search importer organisations"))
				.andExpect(jsonPath("$.paths['/api/organisations'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/organisations'].get.parameters[?(@.name == 'type')].required",
						contains(true)))
				.andExpect(jsonPath("$.paths['/api/organisations'].get.responses['200'].content['application/json'].schema.$ref")
						.value("#/components/schemas/OrganisationPageResponse"))
				.andExpect(jsonPath("$.paths['/api/organisations'].get.responses['400'].content['application/json'].example.message")
						.value(InvalidOrganisationQueryException.TYPE))
				.andExpect(jsonPath("$.paths['/api/organisations'].get.responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/organisations'].get.responses['403'].content['application/json'].example.message")
						.value(OrganisationAccessForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.components.schemas.OrganisationPageResponse.properties.items.items.$ref")
						.value("#/components/schemas/Organisation"));
	}

	private String account(String prefix, Long company, String role) throws Exception {
		String email = prefix + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, organisation_id)
				SELECT 'Akil Tan', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), company, role);
		return tokenFor(email);
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM organisations WHERE code = ?", Long.class, code);
	}

	private Long insertCompany(String code, String name) {
		return insertCompany(code, name, true);
	}

	private Long insertCompany(String code, String name, boolean active) {
		jdbc.update("INSERT INTO organisations (code, name, active) VALUES (?, ?, ?)", code, name, active);
		return jdbc.queryForObject("SELECT id FROM organisations WHERE code = ?", Long.class, code);
	}
}
