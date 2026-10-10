package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.drift.backend.shipment.exception.StaleShipmentVersionException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentUpdateApiIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private Long companyId;
	private String forwarderEmail;
	private String forwarderToken;

	@BeforeEach
	void setUp() throws Exception {
		companyId = companyId("HARBOURLINE_DEMO");
		forwarderEmail = "cdg85-forwarder-" + UUID.randomUUID() + "@example.com";
		createAccount(forwarderEmail, "FREIGHT_FORWARDER", companyId);
		forwarderToken = tokenFor(forwarderEmail);
	}

	@Test
	void importerCanReplaceAllEditableFieldsAndReceivesTheNextVersion() throws Exception {
		ShipmentSnapshot shipment = create(forwarderToken, "HBL-UPDATE-ALL");
		String importerEmail = "cdg85-importer-" + UUID.randomUUID() + "@example.com";
		createAccount(importerEmail, "IMPORTER", companyId);

		update(tokenFor(importerEmail), shipment.id(), updatePayload("HBL-UPDATED", shipment.version(),
				"Busan, KR", "Surabaya, ID", "Tanjung Pelepas", "MV New Mother",
				"2026-11-15T08:00:00+08:00", "MV New Feeder", "2026-11-16T12:00:00+08:00"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.shipmentReference").value("HBL-UPDATED"))
				.andExpect(jsonPath("$.origin").value("Busan, KR"))
				.andExpect(jsonPath("$.destination").value("Surabaya, ID"))
				.andExpect(jsonPath("$.transshipmentPort").value("Tanjung Pelepas"))
				.andExpect(jsonPath("$.motherVessel").value("MV New Mother"))
				.andExpect(jsonPath("$.plannedMotherArrivalAt").value("2026-11-15T00:00:00Z"))
				.andExpect(jsonPath("$.feederVessel").value("MV New Feeder"))
				.andExpect(jsonPath("$.plannedFeederDepartureAt").value("2026-11-16T04:00:00Z"))
				.andExpect(jsonPath("$.version").value(shipment.version() + 1));

		Long importerId = accountId(importerEmail);
		assertThat(jdbc.queryForObject("SELECT updated_by_user_id FROM shipments WHERE id = ?", Long.class, shipment.id()))
				.isEqualTo(importerId);
		assertThat(jdbc.queryForObject("SELECT updated_at >= created_at FROM shipments WHERE id = ?", Boolean.class, shipment.id()))
				.isTrue();
	}

	@Test
	void forwarderCanRetainOrChangeAReferenceWhenItRemainsUnique() throws Exception {
		ShipmentSnapshot shipment = create(forwarderToken, "HBL-KEEP");
		update(forwarderToken, shipment.id(), updatePayload("HBL-KEEP", shipment.version()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-KEEP"));

		update(forwarderToken, shipment.id(), updatePayload("HBL-NEW", shipment.version() + 1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-NEW"))
				.andExpect(jsonPath("$.version").value(shipment.version() + 2));
	}

	@Test
	void rejectsDuplicateReferencesWithinTheSameCompany() throws Exception {
		ShipmentSnapshot first = create(forwarderToken, "HBL-FIRST");
		create(forwarderToken, "HBL-SECOND");

		update(forwarderToken, first.id(), updatePayload("hbl-second", first.version()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(DuplicateShipmentReferenceException.MESSAGE));
	}

	@Test
	void rejectsInvalidAndMissingUpdateRequests() throws Exception {
		ShipmentSnapshot shipment = create(forwarderToken, "HBL-INVALID");
		update(forwarderToken, shipment.id(), updatePayload("HBL-INVALID", shipment.version(),
				"Shanghai, CN", "Jakarta, ID", "Singapore", "MV Pacific Horizon",
				"2026-10-16T12:00:00+08:00", "MV Strait Runner", "2026-10-16T12:00:00+08:00"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidItineraryException.MESSAGE));

		update(forwarderToken, shipment.id(), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.version").value("Version is required"));
	}

	@Test
	void rejectsUnauthenticatedAndCrossCompanyUpdates() throws Exception {
		ShipmentSnapshot shipment = create(forwarderToken, "HBL-PRIVATE");
		mvc.perform(put("/api/shipments/" + shipment.id())
				.contentType(MediaType.APPLICATION_JSON)
				.content(updatePayload("HBL-PRIVATE", shipment.version())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));

		String otherEmail = "cdg85-other-" + UUID.randomUUID() + "@example.com";
		createAccount(otherEmail, "IMPORTER", companyId("STRAITS_FRESH_DEMO"));
		update(tokenFor(otherEmail), shipment.id(), updatePayload("HBL-PRIVATE", shipment.version()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
	}

	@Test
	void rejectsStaleVersionsWithConflict() throws Exception {
		ShipmentSnapshot shipment = create(forwarderToken, "HBL-STALE");
		update(forwarderToken, shipment.id(), updatePayload("HBL-CURRENT", shipment.version()))
				.andExpect(status().isOk());

		update(forwarderToken, shipment.id(), updatePayload("HBL-STALE-WRITE", shipment.version()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(StaleShipmentVersionException.MESSAGE));
	}

	private ShipmentSnapshot create(String token, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createPayload(reference)))
				.andExpect(status().isCreated())
				.andReturn();
		String response = result.getResponse().getContentAsString();
		Number shipmentId = JsonPath.read(response, "$.id");
		Number version = JsonPath.read(response, "$.version");
		return new ShipmentSnapshot(shipmentId.longValue(), version.longValue());
	}

	private org.springframework.test.web.servlet.ResultActions update(String token, Long shipmentId, String body) throws Exception {
		return mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private void createAccount(String email, String role, Long accountCompanyId) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Update User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), accountCompanyId, role);
	}

	private Long companyId(String companyCode) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, companyCode);
	}

	private Long accountId(String email) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private static String updatePayload(String reference, Long version) {
		return updatePayload(reference, version, "Shanghai, CN", "Jakarta, ID", "Singapore", "MV Pacific Horizon",
				"2026-10-15T08:00:00+08:00", "MV Strait Runner", "2026-10-16T12:00:00+08:00");
	}

	private static String createPayload(String reference) {
		return """
				{
				  "shipmentReference": "%s",
				  "origin": "Shanghai, CN",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
				}
				""".formatted(reference);
	}

	private static String updatePayload(String reference, Long version, String origin, String destination,
			String transshipmentPort, String motherVessel, String motherArrival, String feederVessel, String feederDeparture) {
		return """
				{
				  "shipmentReference": "%s",
				  "origin": "%s",
				  "destination": "%s",
				  "transshipmentPort": "%s",
				  "motherVessel": "%s",
				  "plannedMotherArrivalAt": "%s",
				  "feederVessel": "%s",
				  "plannedFeederDepartureAt": "%s",
				  "version": %s
				}
				""".formatted(reference, origin, destination, transshipmentPort, motherVessel, motherArrival, feederVessel,
						feederDeparture, version);
	}

	private record ShipmentSnapshot(Long id, Long version) {
	}
}
