package com.drift.backend.importerauthorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
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

import jakarta.persistence.EntityManager;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImporterAuthorisationIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private Long forwarderCompanyId;
	private Long importerCompanyId;
	private String adminToken;
	private String forwarderToken;

	@BeforeEach
	void accounts() throws Exception {
		forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		String adminEmail = "cdg338-admin-" + UUID.randomUUID() + "@example.com";
		String forwarderEmail = "cdg338-forwarder-" + UUID.randomUUID() + "@example.com";
		createAccount(adminEmail, forwarderCompanyId, "ADMIN");
		createAccount(forwarderEmail, forwarderCompanyId, "FREIGHT_FORWARDER");
		adminToken = tokenFor(adminEmail);
		forwarderToken = tokenFor(forwarderEmail);
	}

	@Test
	void anAdministratorGrantsAndListsAnAuthorisationWithoutLinkingAShipment() throws Exception {
		Long shipmentId = createShipment(forwarderToken);
		MvcResult granted = grant(adminToken, importerCompanyId)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.importer.id").value(importerCompanyId))
				.andExpect(jsonPath("$.importer.code").value("STRAITS_FRESH_DEMO"))
				.andExpect(jsonPath("$.active").value(true))
				.andReturn();
		Number authorisationId = JsonPath.read(granted.getResponse().getContentAsString(), "$.id");

		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorisations[?(@.id == " + authorisationId + ")].active").value(hasItem(true)));
		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorisations[?(@.id == " + authorisationId + ")].importer.code")
						.value(hasItem("STRAITS_FRESH_DEMO")));
		assertThat(relationshipCount(forwarderCompanyId, importerCompanyId)).isEqualTo(1);
		assertThat(importerOn(shipmentId)).isNull();
	}

	@Test
	void repeatingAGrantKeepsOneRowAndReauthorisingRestoresIt() throws Exception {
		Number firstId = JsonPath.read(grant(adminToken, importerCompanyId).andReturn().getResponse().getContentAsString(), "$.id");
		Number secondId = JsonPath.read(grant(adminToken, importerCompanyId).andReturn().getResponse().getContentAsString(), "$.id");
		assertThat(secondId.longValue()).isEqualTo(firstId.longValue());
		assertThat(relationshipCount(forwarderCompanyId, importerCompanyId)).isEqualTo(1);

		Long shipmentId = createShipment(forwarderToken);
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerCompanyId, shipmentId);
		end(adminToken, firstId.longValue()).andExpect(jsonPath("$.active").value(false));
		assertThat(importerOn(shipmentId)).isEqualTo(importerCompanyId);

		end(adminToken, firstId.longValue()).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		assertThat(importerOn(shipmentId)).isEqualTo(importerCompanyId);

		MvcResult restored = grant(adminToken, importerCompanyId)
				.andExpect(jsonPath("$.active").value(true))
				.andReturn();
		Number restoredId = JsonPath.read(restored.getResponse().getContentAsString(), "$.id");
		assertThat(restoredId.longValue()).isEqualTo(firstId.longValue());
		entityManager.flush();
		assertThat(jdbc.queryForObject("""
				SELECT active FROM forwarder_importer_relationships WHERE id = ?
				""", Boolean.class, firstId.longValue())).isTrue();
		assertThat(importerOn(shipmentId)).isEqualTo(importerCompanyId);
		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(jsonPath("$.authorisations[?(@.id == " + firstId + ")]").exists());
	}

	@Test
	void endingRemovesTheAuthorisationFromTheActiveListAndLeavesAnExistingLink() throws Exception {
		Number authorisationId = JsonPath.read(grant(adminToken, importerCompanyId).andReturn().getResponse().getContentAsString(), "$.id");
		Long shipmentId = createShipment(forwarderToken);
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerCompanyId, shipmentId);

		end(adminToken, authorisationId.longValue())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(jsonPath("$.authorisations[?(@.id == " + authorisationId + ")]").isEmpty());
		assertThat(importerOn(shipmentId)).isEqualTo(importerCompanyId);
	}

	@Test
	void refusesTheWrongRoleAnotherCompanyAndAnInvalidImporter() throws Exception {
		String importerEmail = "cdg338-importer-" + UUID.randomUUID() + "@example.com";
		String managerEmail = "cdg338-manager-" + UUID.randomUUID() + "@example.com";
		createAccount(importerEmail, importerCompanyId, "IMPORTER");
		createAccount(managerEmail, forwarderCompanyId, "LOGISTICS_MANAGER");
		Long otherCompanyId = insertCompany("CDG338_OTHER", "Other Forwarder", true);
		String otherAdminEmail = "cdg338-other-admin-" + UUID.randomUUID() + "@example.com";
		createAccount(otherAdminEmail, otherCompanyId, "ADMIN");
		String otherAdminToken = tokenFor(otherAdminEmail);
		Number authorisationId = JsonPath.read(grant(adminToken, importerCompanyId).andReturn().getResponse().getContentAsString(), "$.id");

		grant(forwarderToken, importerCompanyId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationForbiddenException.CHANGE));
		grant(tokenFor(importerEmail), importerCompanyId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationForbiddenException.CHANGE));
		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + tokenFor(managerEmail)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationForbiddenException.VIEW));
		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + otherAdminToken))
				.andExpect(jsonPath("$.authorisations[?(@.id == " + authorisationId + ")]").isEmpty());
		end(otherAdminToken, authorisationId.longValue())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationNotFoundException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT active FROM forwarder_importer_relationships WHERE id = ?", Boolean.class,
				authorisationId.longValue())).isTrue();

		grant(adminToken, forwarderCompanyId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		Long dormantId = insertCompany("CDG338_DORMANT", "Dormant Imports", false);
		grant(adminToken, dormantId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		grant(adminToken, 999999999L)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/importer-authorisations"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		end(adminToken, 999999999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationNotFoundException.MESSAGE));
	}

	@Test
	void anInactiveCompanyCannotGrantOrViewAuthorisations() throws Exception {
		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", forwarderCompanyId);
		entityManager.flush();
		entityManager.clear();
		grant(adminToken, importerCompanyId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationForbiddenException.ACTIVE_COMPANY));
		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationForbiddenException.ACTIVE_COMPANY));
	}

	private org.springframework.test.web.servlet.ResultActions grant(String token, Long importerId) throws Exception {
		return mvc.perform(post("/api/importer-authorisations")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerId + "}"));
	}

	private org.springframework.test.web.servlet.ResultActions end(String token, Long authorisationId) throws Exception {
		return mvc.perform(post("/api/importer-authorisations/" + authorisationId + "/end")
				.header("Authorization", "Bearer " + token));
	}

	private Long createShipment(String token) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "CDG338-%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						""".formatted(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		return id.longValue();
	}

	private void createAccount(String email, Long companyId, String role) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 338', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long insertCompany(String code, String name, boolean active) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, ?)", code, name, active);
		return companyId(code);
	}

	private Integer relationshipCount(Long forwarderId, Long importerId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM forwarder_importer_relationships
				WHERE forwarder_company_id = ? AND importer_company_id = ?
				""", Integer.class, forwarderId, importerId);
	}

	private Long importerOn(Long shipmentId) {
		return jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId);
	}
}
