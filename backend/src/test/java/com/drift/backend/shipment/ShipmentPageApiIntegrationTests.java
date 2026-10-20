package com.drift.backend.shipment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG169_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG169_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG169_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG169_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG169_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG169_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG169_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG169_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentPageApiDatabaseGuard.class)
@Transactional
class ShipmentPageApiIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private CompanyFixture forwarder;
	private CompanyFixture linkedImporter;
	private CompanyFixture unrelated;
	private String forwarderToken;
	private String linkedImporterToken;
	private String unrelatedToken;

	@BeforeEach
	void setUp() throws Exception {
		forwarder = company("API Forwarder");
		linkedImporter = company("API Linked Importer");
		unrelated = company("API Unrelated Importer");
		forwarderToken = tokenFor(account(forwarder, "FREIGHT_FORWARDER"));
		linkedImporterToken = tokenFor(account(linkedImporter, "IMPORTER"));
		unrelatedToken = tokenFor(account(unrelated, "IMPORTER"));
	}

	@Test
	void omittedPagingParametersKeepTheAuthorisedJsonArray() throws Exception {
		insert(forwarder, null, "HBL-OLDER", "MV Older", "MV Feeder", "2026-10-12T08:00:00+08:00", "2026-10-01T00:00:00Z");
		insert(forwarder, linkedImporter, "HBL-NEWER", "MV Newer", "MV Feeder", "2026-10-12T09:00:00+08:00", "2026-10-02T00:00:00Z");
		insert(unrelated, null, "HBL-HIDDEN", "MV Hidden", "MV Feeder", "2026-10-12T08:00:00+08:00", "2026-10-03T00:00:00Z");

		list(forwarderToken).andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-NEWER"))
				.andExpect(jsonPath("$[1].shipmentReference").value("HBL-OLDER"))
				.andExpect(jsonPath("$.length()").value(2));
		list(forwarderToken, "reference", "newer").andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-NEWER"))
				.andExpect(jsonPath("$.length()").value(1));
		list(linkedImporterToken).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-NEWER"));
	}

	@Test
	void pagingParametersReturnAFixedPageAndDefaultTheOmittedMembers() throws Exception {
		for (int index = 0; index < 26; index++) {
			insert(forwarder, null, "HBL-" + String.format("%02d", index), "MV Page", "MV Feeder",
					"2026-10-12T08:00:00+08:00", "2026-10-01T00:00:%02dZ".formatted(index));
		}
		insert(unrelated, null, "HBL-HIDDEN", "MV Hidden", "MV Feeder", "2026-10-12T08:00:00+08:00", "2026-10-03T00:00:00Z");

		list(forwarderToken, "page", "1").andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(25))
				.andExpect(jsonPath("$.total").value(26))
				.andExpect(jsonPath("$.items.length()").value(25))
				.andExpect(jsonPath("$.items[0].shipmentReference").value("HBL-25"))
				.andExpect(jsonPath("$.items[24].shipmentReference").value("HBL-01"));
		list(forwarderToken, "page", "2").andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(26))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].shipmentReference").value("HBL-00"));
		list(forwarderToken, "sort", "reference").andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.items[0].shipmentReference").value("HBL-25"))
				.andExpect(jsonPath("$.items[1].shipmentReference").value("HBL-24"));
		list(forwarderToken, "direction", "asc").andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.items[0].shipmentReference").value("HBL-00"))
				.andExpect(jsonPath("$.items[1].shipmentReference").value("HBL-01"));
	}

	@Test
	void pagingCombinesWithFiltersAndCountsOnlyAuthorisedShipments() throws Exception {
		insert(forwarder, linkedImporter, "HBL-KEEP-LATE", "MV Pacific", "MV Feeder", "2026-10-12T13:00:00+08:00", "2026-10-01T00:00:00Z");
		insert(forwarder, linkedImporter, "HBL-KEEP-EARLY", "MV Pacific", "MV Feeder", "2026-10-12T09:00:00+08:00", "2026-10-01T00:00:01Z");
		insert(forwarder, linkedImporter, "HBL-OTHER", "MV Other", "MV Feeder", "2026-10-12T08:00:00+08:00", "2026-10-01T00:00:02Z");
		insert(forwarder, linkedImporter, "HBL-KEEP-ARCHIVED", "MV Pacific", "MV Feeder", "2026-10-12T11:00:00+08:00", "2026-10-01T00:00:03Z");
		insert(unrelated, null, "HBL-KEEP-HIDDEN", "MV Pacific", "MV Feeder", "2026-10-12T08:00:00+08:00", "2026-10-01T00:00:04Z");
		archive("HBL-KEEP-ARCHIVED");

		list(forwarderToken, "page", "1", "sort", "plannedArrival", "direction", "asc", "reference", "keep",
				"vessel", "pacific", "customer", linkedImporter.code()).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].shipmentReference").value("HBL-KEEP-EARLY"))
				.andExpect(jsonPath("$.items[1].shipmentReference").value("HBL-KEEP-LATE"));
		list(forwarderToken, "reference", "keep", "vessel", "pacific", "customer", linkedImporter.code())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(2));
		list(forwarderToken, "page", "1", "status", "ARCHIVED", "reference", "keep").andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].shipmentReference").value("HBL-KEEP-ARCHIVED"));
		list(linkedImporterToken, "page", "1").andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.items[?(@.shipmentReference == 'HBL-KEEP-HIDDEN')]").isEmpty());
		list(unrelatedToken, "page", "1", "reference", "keep").andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].shipmentReference").value("HBL-KEEP-HIDDEN"));
	}

	@Test
	void outOfRangePageIsEmptyAndKeepsTheTotal() throws Exception {
		insert(forwarder, null, "HBL-ONLY", "MV Only", "MV Feeder", "2026-10-12T08:00:00+08:00", "2026-10-01T00:00:00Z");

		list(forwarderToken, "page", "3").andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(3))
				.andExpect(jsonPath("$.size").value(25))
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.total").value(1));
		list(forwarderToken, "page", "2", "reference", "missing").andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void rejectsInvalidPagingParametersAndNumericOverflow() throws Exception {
		list(forwarderToken, "page", "0").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.page").value(ShipmentListQuery.PAGE_ERROR));
		list(forwarderToken, "page", "-1").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").value(ShipmentListQuery.PAGE_ERROR));
		list(forwarderToken, "page", "foo").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").value(ShipmentListQuery.PAGE_ERROR));
		list(forwarderToken, "page", "").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").value(ShipmentListQuery.PAGE_ERROR));
		list(forwarderToken, "page", "2147483648").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").value(ShipmentListQuery.PAGE_ERROR));
		list(forwarderToken, "page", "999999999999999999999").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").value(ShipmentListQuery.PAGE_ERROR));
		list(forwarderToken, "sort", "risk").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.sort").value(ShipmentListQuery.SORT_ERROR));
		list(forwarderToken, "direction", "descending").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.direction").value(ShipmentListQuery.DIRECTION_ERROR));
		list(forwarderToken, "size", "25").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.size").value(ShipmentListQuery.SIZE_ERROR));
		list(forwarderToken, "size", "").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.size").value(ShipmentListQuery.SIZE_ERROR));
		list(forwarderToken, "page", "nope", "sort", "risk", "size", "10").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").value(ShipmentListQuery.PAGE_ERROR))
				.andExpect(jsonPath("$.errors.sort").value(ShipmentListQuery.SORT_ERROR))
				.andExpect(jsonPath("$.errors.size").value(ShipmentListQuery.SIZE_ERROR));
	}

	@Test
	void documentsTheArrayAndThePagedResponse() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/shipments'].get.description")
						.value(Matchers.containsString("Archived shipments are excluded")))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.description")
						.value(Matchers.containsString("JSON array")))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.description")
						.value(Matchers.containsString("page object")))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.parameters[?(@.name == 'page')].description")
						.value(Matchers.hasItem(Matchers.containsString("One-based page"))))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.parameters[?(@.name == 'sort')].description")
						.value(Matchers.hasItem(Matchers.containsString("plannedArrival"))))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.parameters[?(@.name == 'direction')].description")
						.value(Matchers.hasItem(Matchers.containsString("asc or desc"))))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.parameters[?(@.name == 'size')].description")
						.value(Matchers.hasItem(Matchers.containsString("fixed at 25"))))
				.andExpect(jsonPath("$.components.schemas.ShipmentPage.properties.items").exists())
				.andExpect(jsonPath("$.components.schemas.ShipmentPage.properties.page").exists())
				.andExpect(jsonPath("$.components.schemas.ShipmentPage.properties.size").exists())
				.andExpect(jsonPath("$.components.schemas.ShipmentPage.properties.total").exists());
	}

	private ResultActions list(String token, String... parameters) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/shipments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		for (int index = 0; index < parameters.length; index += 2) {
			request.param(parameters[index], parameters[index + 1]);
		}
		return mvc.perform(request);
	}

	private CompanyFixture company(String name) {
		String code = "CDG169_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, name);
		Long id = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
		return new CompanyFixture(id, code);
	}

	private String account(CompanyFixture company, String role) {
		String email = "cdg169-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-169 User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), company.id(), role);
		return email;
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private void insert(CompanyFixture managingCompany, CompanyFixture importerCompany, String reference,
			String motherVessel, String feederVessel, String arrival, String createdAt) {
		Long creatorId = jdbc.queryForObject("SELECT id FROM users WHERE company_id = ? ORDER BY id LIMIT 1", Long.class,
				managingCompany.id());
		OffsetDateTime plannedArrival = OffsetDateTime.parse(arrival);
		jdbc.update("""
				INSERT INTO shipments (
					company_id, importer_company_id, created_by_user_id, updated_by_user_id,
					shipment_reference, origin, destination, transshipment_port, mother_vessel,
					planned_mother_arrival_at, feeder_vessel, planned_feeder_departure_at,
					created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'Singapore', 'Rotterdam', 'Tanjung Pelepas', ?, ?, ?, ?, ?, ?, 0)
				""", managingCompany.id(), importerCompany == null ? null : importerCompany.id(), creatorId, creatorId,
				reference, motherVessel, plannedArrival, feederVessel, plannedArrival.plusHours(8),
				Timestamp.from(Instant.parse(createdAt)), Timestamp.from(Instant.parse(createdAt)));
	}

	private void archive(String reference) {
		jdbc.update("UPDATE shipments SET status = 'ARCHIVED' WHERE shipment_reference = ?", reference);
	}

	private record CompanyFixture(Long id, String code) {
	}
}
