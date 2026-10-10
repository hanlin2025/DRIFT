package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.drift.backend.shipment.exception.MissingShipmentInformationException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentLinkIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private String email;
	private Long companyId;
	private Long importerId;
	private String token;

	@BeforeEach
	void account() throws Exception {
		email = "cdg121-" + UUID.randomUUID() + "@example.com";
		companyId = companyId("HARBOURLINE_DEMO");
		importerId = companyId("STRAITS_FRESH_DEMO");
		createAccount(email, companyId, "FREIGHT_FORWARDER");
		token = tokenFor(email);
	}

	@Test
	void linksAnotherActiveCompanyAndReturnsTheShipmentDetails() throws Exception {
		Long shipmentId = createShipment(token);
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.id").value(shipmentId))
				.andExpect(jsonPath("$.shipmentReference").value("HBL-2026-001"))
				.andExpect(jsonPath("$.importer.id").value(importerId))
				.andExpect(jsonPath("$.importer.code").value("STRAITS_FRESH_DEMO"))
				.andExpect(jsonPath("$.importer.name").value("Straits Fresh Imports (Demo)"))
				.andExpect(jsonPath("$.connectionWindow.duration").value("1 day 4 hours"));

		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isEqualTo(importerId);
		mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importer.id").value(importerId));
	}

	@Test
	void replacesTheLinkAndThenRemovesIt() throws Exception {
		Long shipmentId = createShipment(token);
		Long replacementId = insertCompany("CDG121_OTHER", "Other Imports", true);
		link(token, shipmentId, importerId).andExpect(status().isOk());
		link(token, shipmentId, replacementId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importer.id").value(replacementId));
		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isEqualTo(replacementId);

		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":null}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importer").value(org.hamcrest.Matchers.nullValue()));
		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isNull();
	}

	@Test
	void rejectsAMissingInactiveOrOwnCompany() throws Exception {
		Long shipmentId = createShipment(token);
		Long dormantId = insertCompany("CDG121_DORMANT", "Dormant Imports", false);
		link(token, shipmentId, 999999999L)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		link(token, shipmentId, dormantId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		link(token, shipmentId, companyId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isNull();
	}

	@Test
	void rejectsAnImporterAndAFreightForwarderFromAnotherCompany() throws Exception {
		Long shipmentId = createShipment(token);
		String importerEmail = "cdg121-importer-" + UUID.randomUUID() + "@example.com";
		createAccount(importerEmail, importerId, "IMPORTER");
		String otherForwarder = "cdg121-other-" + UUID.randomUUID() + "@example.com";
		createAccount(otherForwarder, importerId, "FREIGHT_FORWARDER");

		link(tokenFor(importerEmail), shipmentId, importerId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentLinkForbiddenException.MESSAGE));
		link(tokenFor(otherForwarder), shipmentId, companyId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentLinkForbiddenException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isNull();
	}

	@Test
	void rejectsAMissingBodyWithoutClearingAnExistingLink() throws Exception {
		Long shipmentId = createShipment(token);
		link(token, shipmentId, importerId).andExpect(status().isOk());

		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(MissingShipmentInformationException.MESSAGE));

		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isEqualTo(importerId);
	}

	@Test
	void rejectsAMissingSessionAndAnUnknownShipment() throws Exception {
		mvc.perform(patch("/api/shipments/1/link-importer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		link(token, 999999999L, importerId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		mvc.perform(patch("/api/shipments/not-a-number/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
	}

	private org.springframework.test.web.servlet.ResultActions link(String bearerToken, Long shipmentId, Long importerCompanyId) throws Exception {
		return mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"));
	}

	private Long createShipment(String bearerToken) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "HBL-2026-001",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						"""))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		return id.longValue();
	}

	private void createAccount(String accountEmail, Long accountCompanyId, String role) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Alice Tan', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", accountEmail, passwords.encode("Example123"), accountCompanyId, role);
	}

	private String tokenFor(String accountEmail) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(accountEmail)))
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
}
