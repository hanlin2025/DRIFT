package com.drift.backend.shipment;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentImporterIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private Long harbourlineId;
	private Long straitsId;
	private String forwarderToken;

	@BeforeEach
	void account() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		straitsId = companyId("STRAITS_FRESH_DEMO");
		String email = "cdg25-" + UUID.randomUUID() + "@example.com";
		createAccount(email, harbourlineId, "FREIGHT_FORWARDER");
		forwarderToken = tokenFor(email);
	}

	@Test
	void linksAShipmentOnCreateAndShowsItOnlyToThatImporter() throws Exception {
		String straitsImporter = "cdg25-straits-" + UUID.randomUUID() + "@example.com";
		createAccount(straitsImporter, straitsId, "IMPORTER");
		String homeImporter = "cdg25-home-" + UUID.randomUUID() + "@example.com";
		createAccount(homeImporter, harbourlineId, "IMPORTER");

		MvcResult created = create(forwarderToken, shipment("HBL-LINK-1", straitsId))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.importerOrganisation.name").value("Straits Fresh Imports (Demo)"))
				.andReturn();
		Number shipmentId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		list(tokenFor(straitsImporter))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-LINK-1"))
				.andExpect(jsonPath("$[0].importerOrganisation.id").value(straitsId.intValue()));
		detail(tokenFor(straitsImporter), shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-LINK-1"));
		list(tokenFor(homeImporter))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-LINK-1"));

		create(forwarderToken, shipment("HBL-PRIVATE", null)).andExpect(status().isCreated());
		list(tokenFor(straitsImporter))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
		String listed = list(forwarderToken).andReturn().getResponse().getContentAsString();
		java.util.List<Integer> ids = JsonPath.read(listed, "$[?(@.shipmentReference == 'HBL-PRIVATE')].id");
		detail(tokenFor(straitsImporter), ids.get(0))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		mvc.perform(get("/api/shipments/" + shipmentId + "/tracking")
				.header("Authorization", "Bearer " + tokenFor(straitsImporter)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselName").value("MV Pacific Horizon"));
	}

	@Test
	void showsEachImporterOnlyTheShipmentLinkedToThatOrganisation() throws Exception {
		Long northwindId = jdbc.queryForObject("""
				INSERT INTO companies (code, name, active)
				VALUES ('NORTHWIND_DEMO', 'Northwind Imports', TRUE)
				RETURNING id
				""", Long.class);
		String straitsImporter = "cdg25-only-straits-" + UUID.randomUUID() + "@example.com";
		String northwindImporter = "cdg25-only-north-" + UUID.randomUUID() + "@example.com";
		createAccount(straitsImporter, straitsId, "IMPORTER");
		createAccount(northwindImporter, northwindId, "IMPORTER");

		create(forwarderToken, shipment("HBL-FOR-STRAITS", straitsId)).andExpect(status().isCreated());
		create(forwarderToken, shipment("HBL-FOR-NORTH", northwindId)).andExpect(status().isCreated());

		list(tokenFor(straitsImporter))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-FOR-STRAITS"));
		list(tokenFor(northwindImporter))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-FOR-NORTH"));
	}

	@Test
	void movesTheShipmentWhenTheImporterChangesAndRemovesItWhenUnlinked() throws Exception {
		String straitsImporter = "cdg25-move-" + UUID.randomUUID() + "@example.com";
		createAccount(straitsImporter, straitsId, "IMPORTER");
		Number shipmentId = JsonPath.read(create(forwarderToken, shipment("HBL-MOVE", straitsId))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(put("/api/shipments/" + shipmentId + "/importer")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":null}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importerOrganisation").value(nullValue()));
		list(tokenFor(straitsImporter)).andExpect(jsonPath("$.length()").value(0));

		mvc.perform(put("/api/shipments/" + shipmentId + "/importer")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + straitsId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importerOrganisation.name").value("Straits Fresh Imports (Demo)"));
		list(tokenFor(straitsImporter))
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-MOVE"));
	}

	@Test
	void rejectsAnInactiveMissingOrOwnOrganisation() throws Exception {
		create(forwarderToken, shipment("HBL-OWN", harbourlineId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		create(forwarderToken, shipment("HBL-GONE", 999999L))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));

		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", straitsId);
		entityManager.clear();
		create(forwarderToken, shipment("HBL-INACTIVE", straitsId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
	}

	@Test
	void forbidsLinkingByAnImporterOrAnotherCompany() throws Exception {
		Number shipmentId = JsonPath.read(create(forwarderToken, shipment("HBL-GUARD", null))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		String importer = "cdg25-forbid-" + UUID.randomUUID() + "@example.com";
		createAccount(importer, harbourlineId, "IMPORTER");
		mvc.perform(put("/api/shipments/" + shipmentId + "/importer")
				.header("Authorization", "Bearer " + tokenFor(importer))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + straitsId + "}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentLinkForbiddenException.MESSAGE));

		String otherForwarder = "cdg25-other-ff-" + UUID.randomUUID() + "@example.com";
		createAccount(otherForwarder, straitsId, "FREIGHT_FORWARDER");
		mvc.perform(put("/api/shipments/" + shipmentId + "/importer")
				.header("Authorization", "Bearer " + tokenFor(otherForwarder))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + harbourlineId + "}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentLinkForbiddenException.MESSAGE));

		String sameCompanyImporter = "cdg25-create-forbid-" + UUID.randomUUID() + "@example.com";
		createAccount(sameCompanyImporter, harbourlineId, "IMPORTER");
		create(tokenFor(sameCompanyImporter), shipment("HBL-IMPORTER-LINK", straitsId))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentLinkForbiddenException.MESSAGE));

		mvc.perform(put("/api/shipments/not-a-number/importer")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + straitsId + "}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void listsOtherActiveOrganisationsAndHidesTheCallersCompany() throws Exception {
		mvc.perform(get("/api/importer-organisations").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$[?(@.code == 'HARBOURLINE_DEMO')]").isEmpty())
				.andExpect(jsonPath("$[?(@.code == 'STRAITS_FRESH_DEMO')].name", contains("Straits Fresh Imports (Demo)")));
		mvc.perform(get("/api/importer-organisations").param("q", "straits").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/importer-organisations").param("q", "no-such-company").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(jsonPath("$.length()").value(0));

		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", harbourlineId);
		entityManager.clear();
		mvc.perform(get("/api/importer-organisations").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));
	}

	@Test
	void documentsTheImporterEndpoints() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/importer-organisations'].get.summary").value("List importer organisations"))
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}/importer'].put.summary").value("Link a shipment to an importer"))
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}/importer'].put.security[0].bearerAuth").exists());
	}

	private org.springframework.test.web.servlet.ResultActions create(String bearerToken, String body) throws Exception {
		return mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private org.springframework.test.web.servlet.ResultActions list(String bearerToken) throws Exception {
		return mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + bearerToken));
	}

	private org.springframework.test.web.servlet.ResultActions detail(String bearerToken, Number shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + bearerToken));
	}

	private void createAccount(String accountEmail, Long accountCompanyId, String role) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role, company_id)
				VALUES ('Alice Tan', ?, ?, ?, ?)
				""", accountEmail, passwords.encode("Example123"), role, accountCompanyId);
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

	private static String shipment(String reference, Long importerCompanyId) {
		String importer = importerCompanyId == null ? "" : ",\n  \"importerCompanyId\": " + importerCompanyId;
		return """
				{
				  "shipmentReference": "%s",
				  "origin": "Shanghai, CN",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"%s
				}
				""".formatted(reference, importer);
	}
}
