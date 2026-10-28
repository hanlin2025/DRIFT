package com.drift.backend.shipment.history;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentHistoryApiIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private Long companyId;
	private Long importerCompanyId;
	private String forwarderName;
	private String token;

	@BeforeEach
	void account() throws Exception {
		companyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarderName = "Forwarder " + UUID.randomUUID();
		token = tokenFor(createAccount(forwarderName, companyId, "FREIGHT_FORWARDER"));
	}

	@Test
	void aNewShipmentHasOnlyItsCreation() throws Exception {
		Long shipmentId = createShipment("HBL-CDG182-NEW");

		history(token, shipmentId)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.shipmentId").value(shipmentId))
				.andExpect(jsonPath("$.earlierChangesUnrecorded").value(false))
				.andExpect(jsonPath("$.entries.length()").value(1))
				.andExpect(jsonPath("$.entries[0].changeType").value("CREATED"))
				.andExpect(jsonPath("$.entries[0].changedBy.fullName").value(forwarderName))
				.andExpect(jsonPath("$.entries[0].recordedAt").isNotEmpty())
				.andExpect(jsonPath("$.entries[0].changes", empty()));
	}

	@Test
	void listsChangesNewestFirstWithTheirFieldValues() throws Exception {
		Long shipmentId = createShipment("HBL-CDG182-EDIT");
		mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipmentJson("HBL-CDG182-EDIT", "Busan, KR", version(shipmentId))))
				.andExpect(status().isOk());
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"))
				.andExpect(status().isOk());

		history(token, shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entries[*].changeType").value(contains("IMPORTER_LINKED", "UPDATED", "CREATED")))
				.andExpect(jsonPath("$.entries[0].changes[0].field").value("importer"))
				.andExpect(jsonPath("$.entries[0].changes[0].previousValue").isEmpty())
				.andExpect(jsonPath("$.entries[0].changes[0].newValue").value(companyName(importerCompanyId)))
				.andExpect(jsonPath("$.entries[1].changes.length()").value(1))
				.andExpect(jsonPath("$.entries[1].changes[0].field").value("origin"))
				.andExpect(jsonPath("$.entries[1].changes[0].previousValue").value("Shanghai, CN"))
				.andExpect(jsonPath("$.entries[1].changes[0].newValue").value("Busan, KR"));
	}

	@Test
	void aLinkedImporterCanReadTheHistory() throws Exception {
		Long shipmentId = createShipment("HBL-CDG182-IMPORTER");
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerCompanyId, shipmentId);
		entityManager.clear();
		String importerToken = tokenFor(createAccount("Importer User", importerCompanyId, "IMPORTER"));

		history(importerToken, shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entries[0].changeType").value("CREATED"));
	}

	@Test
	void anotherCompanyIsForbidden() throws Exception {
		Long shipmentId = createShipment("HBL-CDG182-PRIVATE");
		String otherToken = tokenFor(createAccount("Other User", importerCompanyId, "IMPORTER"));

		history(otherToken, shipmentId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));
	}

	@Test
	void flagsAShipmentWhoseEarlierChangesWereNotRecorded() throws Exception {
		Long shipmentId = createShipment("HBL-CDG182-LEGACY");
		jdbc.update("UPDATE shipment_change_events SET earlier_changes_unrecorded = TRUE WHERE shipment_id = ?",
				shipmentId);
		entityManager.clear();

		history(token, shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.earlierChangesUnrecorded").value(true));
	}

	@Test
	void reportsUnknownAndMalformedIdsAsNotFound() throws Exception {
		history(token, 999999999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		mvc.perform(get("/api/shipments/not-a-number/history").header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
	}

	@Test
	void requiresAuthentication() throws Exception {
		Long shipmentId = createShipment("HBL-CDG182-ANON");

		mvc.perform(get("/api/shipments/" + shipmentId + "/history"))
				.andExpect(status().isUnauthorized());
	}

	private ResultActions history(String bearerToken, Long shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId + "/history")
				.header("Authorization", "Bearer " + bearerToken));
	}

	private Long createShipment(String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipmentJson(reference, "Shanghai, CN", null)))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		return id.longValue();
	}

	private static String shipmentJson(String reference, String origin, Long version) {
		return """
				{
				  "shipmentReference": "%s",
				  "origin": "%s",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"%s
				}
				""".formatted(reference, origin, version == null ? "" : ",\n  \"version\": " + version);
	}

	private Long version(Long shipmentId) {
		return jdbc.queryForObject("SELECT version FROM shipments WHERE id = ?", Long.class, shipmentId);
	}

	private String createAccount(String fullName, Long accountCompanyId, String role) {
		String email = "cdg182-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ?
				FROM roles WHERE code = ?
				""", fullName, email, passwords.encode("Example123"), accountCompanyId, role);
		return email;
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

	private String companyName(Long id) {
		return jdbc.queryForObject("SELECT name FROM companies WHERE id = ?", String.class, id);
	}
}
