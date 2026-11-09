package com.drift.backend.organisation;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.account.registration.invitation.InvitationToken;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;
import com.drift.backend.organisation.exception.InvalidOrganisationException;
import com.drift.backend.organisation.exception.OrganisationCreationForbiddenException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganisationCreationIntegrationTests {

	private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private String adminToken;
	private String forwarderToken;
	private Long harbourlineId;
	private Long straitsId;

	@BeforeEach
	void administrator() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		straitsId = companyId("STRAITS_FRESH_DEMO");
		adminToken = tokenFor(createUser("cdg313-admin-", "ADMIN", harbourlineId));
		forwarderToken = tokenFor(createUser("cdg313-ff-", "FREIGHT_FORWARDER", straitsId));
	}

	@Test
	void addsAnOrganisationWithoutAShipmentARelationshipOrAnInvitation() throws Exception {
		String code = code();
		int companiesBefore = count("SELECT COUNT(*) FROM companies");
		int relationshipsBefore = count("SELECT COUNT(*) FROM forwarder_importer_relationships");
		int shipmentsBefore = count("SELECT COUNT(*) FROM shipments");
		int usersBefore = count("SELECT COUNT(*) FROM users");
		int invitationsBefore = count("SELECT COUNT(*) FROM invitations");

		mvc.perform(post("/api/admin/organisations").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("  " + code.toLowerCase() + "  ", "  Pacific Gate Imports  ")))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.name").value("Pacific Gate Imports"));

		Long createdId = companyId(code);
		assertThat(count("SELECT COUNT(*) FROM companies")).isEqualTo(companiesBefore + 1);
		assertThat(count("SELECT COUNT(*) FROM forwarder_importer_relationships")).isEqualTo(relationshipsBefore);
		assertThat(count("SELECT COUNT(*) FROM shipments")).isEqualTo(shipmentsBefore);
		assertThat(count("SELECT COUNT(*) FROM users")).isEqualTo(usersBefore);
		assertThat(count("SELECT COUNT(*) FROM invitations")).isEqualTo(invitationsBefore);
		assertThat(jdbc.queryForObject("SELECT active FROM companies WHERE id = ?", Boolean.class, createdId)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM shipments WHERE company_id = " + createdId
				+ " OR importer_company_id = " + createdId)).isZero();
		assertThat(count("SELECT COUNT(*) FROM users WHERE company_id = " + createdId)).isZero();
		assertThat(jdbc.queryForObject("SELECT name FROM companies WHERE code = 'HARBOURLINE_DEMO'", String.class))
				.isEqualTo("Harbourline Logistics (Demo)");
		assertThat(jdbc.queryForObject("SELECT name FROM companies WHERE code = 'STRAITS_FRESH_DEMO'", String.class))
				.isEqualTo("Straits Fresh Imports (Demo)");

		mvc.perform(get("/api/admin/organisations").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == '" + code + "')]").isEmpty())
				.andExpect(jsonPath("$[?(@.code == 'HARBOURLINE_DEMO')].name").value("Harbourline Logistics (Demo)"));

		mvc.perform(get("/api/organisations").param("type", "importer").param("name", "Pacific Gate")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].code").value(code))
				.andExpect(jsonPath("$.items[0].name").value("Pacific Gate Imports"));

		jdbc.update("""
				INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
				VALUES (?, ?, 'IMPORTER', ?, NOW() + INTERVAL '1 hour')
				""", "cdg313-" + UUID.randomUUID() + "@example.com", createdId, InvitationToken.hash(TOKEN));
		mvc.perform(post("/api/invitations/resolve").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + TOKEN + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.companyName").value("Pacific Gate Imports"))
				.andExpect(jsonPath("$.role").value("IMPORTER"));
	}

	@Test
	void rejectsADuplicateCodeWithoutChangingTheExistingOrganisation() throws Exception {
		String code = code();
		create(adminToken, code, "Pacific Gate Imports").andExpect(status().isCreated());

		create(adminToken, code.toLowerCase(), "Another Name")
				.andExpect(status().isConflict())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(DuplicateOrganisationException.MESSAGE));
		create(adminToken, "HARBOURLINE_DEMO", "Replacement Harbourline")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(DuplicateOrganisationException.MESSAGE));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE UPPER(code) = ?", Integer.class, code)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT name FROM companies WHERE code = ?", String.class, code))
				.isEqualTo("Pacific Gate Imports");
		assertThat(jdbc.queryForObject("SELECT name FROM companies WHERE code = 'HARBOURLINE_DEMO'", String.class))
				.isEqualTo("Harbourline Logistics (Demo)");
	}

	@Test
	void rejectsAnInvalidIdentityAndDoesNotInsertACompany() throws Exception {
		int before = count("SELECT COUNT(*) FROM companies");

		create(adminToken, "   ", "Pacific Gate Imports")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationException.CODE));
		create(adminToken, "1GATE", "Pacific Gate Imports")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationException.CODE));
		create(adminToken, "PACIFIC_GATE", "   ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationException.NAME));
		mvc.perform(post("/api/admin/organisations").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"PACIFIC_GATE\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationException.NAME));
		mvc.perform(post("/api/admin/organisations").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("not-json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidOrganisationException.CODE));

		assertThat(count("SELECT COUNT(*) FROM companies")).isEqualTo(before);
	}

	@Test
	void deniesAnonymousNonAdminAndAnAdministratorWithoutAnActiveCompany() throws Exception {
		String importer = tokenFor(createUser("cdg313-im-", "IMPORTER", harbourlineId));
		String manager = tokenFor(createUser("cdg313-lm-", "LOGISTICS_MANAGER", harbourlineId));
		String inactive = tokenFor(createUser("cdg313-closed-", "ADMIN", inactiveCompany()));
		int before = count("SELECT COUNT(*) FROM companies");

		mvc.perform(post("/api/admin/organisations").contentType(MediaType.APPLICATION_JSON).content(body("PACIFIC_GATE", "Pacific Gate Imports")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		create(forwarderToken, "PACIFIC_GATE", "Pacific Gate Imports")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		create(importer, "PACIFIC_GATE", "Pacific Gate Imports")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		create(manager, "PACIFIC_GATE", "Pacific Gate Imports")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		create(inactive, "PACIFIC_GATE", "Pacific Gate Imports")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(OrganisationCreationForbiddenException.MESSAGE));

		assertThat(count("SELECT COUNT(*) FROM companies")).isEqualTo(before);
	}

	@Test
	void documentsTheCreateOrganisationApi() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/admin/organisations'].post.summary").value("Add an organisation"))
				.andExpect(jsonPath("$.paths['/api/admin/organisations'].post.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/admin/organisations'].post.responses['201'].content['application/json'].schema.$ref")
						.value("#/components/schemas/Organisation"))
				.andExpect(jsonPath("$.paths['/api/admin/organisations'].post.responses['409'].content['application/json'].example.message")
						.value(DuplicateOrganisationException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/organisations'].get.summary").value("Search importer organisations"));
	}

	private org.springframework.test.web.servlet.ResultActions create(String token, String code, String name) throws Exception {
		return mvc.perform(post("/api/admin/organisations").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body(code, name)));
	}

	private static String body(String code, String name) {
		return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}";
	}

	private static String code() {
		return "CDG313" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private String createUser(String prefix, String role, Long company) {
		String email = prefix + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ?
				FROM roles WHERE code = ?
				""", "CDG 313", email, passwords.encode("Example123"), company, role);
		entityManager.clear();
		return email;
	}

	private Long inactiveCompany() {
		String code = "CDG313CLOSED" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, 'Closed Company', false)", code);
		return companyId(code);
	}

	private String tokenFor(String email) throws Exception {
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
}
