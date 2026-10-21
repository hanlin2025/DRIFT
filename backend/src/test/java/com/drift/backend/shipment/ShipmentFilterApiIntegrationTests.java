package com.drift.backend.shipment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.exception.SessionEndedException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG81_FILTER_API_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG81_FILTER_API_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG81_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG81_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG81_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG81_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG81_FILTER_API_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG81_FILTER_API_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentDisposableDatabaseGuard.class)
@Transactional
class ShipmentFilterApiIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private CompanyFixture forwarder;
	private CompanyFixture linkedImporter;
	private CompanyFixture otherImporter;
	private CompanyFixture unrelated;
	private CompanyFixture rivalForwarder;
	private String forwarderToken;
	private String linkedImporterToken;
	private String unrelatedToken;
	private String rivalForwarderToken;

	@BeforeEach
	void setUp() throws Exception {
		forwarder = company("API Forwarder");
		linkedImporter = company("API Linked Importer");
		otherImporter = company("API Other Importer");
		unrelated = company("API Unrelated Importer");
		rivalForwarder = company("API Rival Forwarder");
		forwarderToken = tokenFor(account(forwarder, "FREIGHT_FORWARDER"));
		linkedImporterToken = tokenFor(account(linkedImporter, "IMPORTER"));
		unrelatedToken = tokenFor(account(unrelated, "IMPORTER"));
		rivalForwarderToken = tokenFor(account(rivalForwarder, "FREIGHT_FORWARDER"));
	}

	@Test
	void listsAuthorizedShipmentsNewestFirstWhenNoFiltersAreSuppliedAndIgnoresBlankText() throws Exception {
		insert(forwarder, null, "HBL-OLDER", "MV Older", "MV Feeder", "2026-10-12T08:00:00+08:00",
				"2026-10-01T00:00:00Z");
		insert(forwarder, null, "HBL-NEWER", "MV Newer", "MV Feeder", "2026-10-12T08:00:00+08:00",
				"2026-10-02T00:00:00Z");
		insert(unrelated, null, "HBL-HIDDEN", "MV Hidden", "MV Feeder", "2026-10-12T08:00:00+08:00",
				"2026-10-03T00:00:00Z");

		list(forwarderToken).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-NEWER"))
				.andExpect(jsonPath("$[0].updatedByName").value("CDG-132 User"))
				.andExpect(jsonPath("$[0].updatedAt").value("2026-10-02T00:00:00Z"))
				.andExpect(jsonPath("$[1].shipmentReference").value("HBL-OLDER"))
				.andExpect(jsonPath("$.length()").value(2));
		list(forwarderToken, "reference", "   ").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-NEWER"))
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void filtersReferenceAndBothVesselFieldsThroughTheHttpContract() throws Exception {
		insert(forwarder, null, "HBL-ALPHA-REF", "MV Pacific Mother", "MV Other", "2026-10-12T08:00:00+08:00",
				"2026-10-01T00:00:00Z");
		insert(forwarder, null, "HBL-BRAVO", "MV Other", "MV Pacific Feeder", "2026-10-12T08:00:00+08:00",
				"2026-10-02T00:00:00Z");

		list(forwarderToken, "reference", "alpha").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-ALPHA-REF"))
				.andExpect(jsonPath("$.length()").value(1));
		list(forwarderToken, "vessel", "pacific mother").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-ALPHA-REF"));
		list(forwarderToken, "vessel", "pacific feeder").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-BRAVO"));
	}

	@Test
	void filtersByLinkedImporterCustomerAndSingaporeArrivalDateAndCombinesCriteria() throws Exception {
		insert(forwarder, linkedImporter, "HBL-MATCH", "MV Pacific Horizon", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-03T00:00:00Z");
		insert(forwarder, linkedImporter, "HBL-WRONG-DATE", "MV Pacific Horizon", "MV Feeder", "2026-10-13T09:00:00+08:00",
				"2026-10-02T00:00:00Z");
		insert(forwarder, otherImporter, "HBL-WRONG-CUSTOMER", "MV Pacific Horizon", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-01T00:00:00Z");
		insert(forwarder, null, "HBL-UNLINKED", "MV Pacific Horizon", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-04T00:00:00Z");

		list(forwarderToken, "customer", linkedImporter.code().toLowerCase()).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-MATCH')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-UNLINKED')]").isEmpty());
		list(forwarderToken, "customer", "linked importer").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-MATCH')]").isNotEmpty());
		list(forwarderToken, "customer", "linked importer", "arrivalDate", "2026-10-12", "reference", "match", "vessel", "pacific")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-MATCH"))
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void returnsStructuredBadRequestForMalformedOrImpossibleArrivalDates() throws Exception {
		list(forwarderToken, "arrivalDate", "banana").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.arrivalDate").value("Arrival date must use ISO format YYYY-MM-DD"));
		list(forwarderToken, "arrivalDate", "2026-02-30").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.arrivalDate").value("Arrival date must use ISO format YYYY-MM-DD"));
	}

	@Test
	void limitsFilteredResultsToShipmentsVisibleToTheActiveCompany() throws Exception {
		insert(forwarder, linkedImporter, "HBL-SHARED", "MV Shared", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-01T00:00:00Z");
		insert(forwarder, otherImporter, "HBL-OTHER", "MV Shared", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-02T00:00:00Z");

		list(linkedImporterToken, "vessel", "shared").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-SHARED"))
				.andExpect(jsonPath("$.length()").value(1));
		list(unrelatedToken, "vessel", "shared").andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void filtersExplicitStatusWithoutCombiningItWithTheDefaultActiveScope() throws Exception {
		insert(forwarder, linkedImporter, "HBL-ACTIVE-STATUS", "MV Pacific Horizon", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-01T00:00:00Z");
		insert(forwarder, linkedImporter, "HBL-ARCHIVED-STATUS", "MV Pacific Horizon", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-02T00:00:00Z");
		insert(unrelated, null, "HBL-HIDDEN-ARCHIVED", "MV Pacific Horizon", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-03T00:00:00Z");
		insert(forwarder, otherImporter, "HBL-OTHER-ARCHIVED", "MV Pacific Horizon", "MV Feeder", "2026-10-12T09:00:00+08:00",
				"2026-10-04T00:00:00Z");
		archive("HBL-ARCHIVED-STATUS");
		archive("HBL-HIDDEN-ARCHIVED");
		archive("HBL-OTHER-ARCHIVED");

		list(forwarderToken).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-ACTIVE-STATUS')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-ARCHIVED-STATUS')]").isEmpty());
		list(forwarderToken, "status", "ACTIVE").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-ACTIVE-STATUS')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-ARCHIVED-STATUS')]").isEmpty());
		list(forwarderToken, "status", "ARCHIVED", "reference", "archived", "vessel", "pacific", "customer",
				linkedImporter.code(), "arrivalDate", "2026-10-12").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-ARCHIVED-STATUS"))
				.andExpect(jsonPath("$.length()").value(1));
		list(linkedImporterToken, "status", "ARCHIVED").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-ARCHIVED-STATUS"))
				.andExpect(jsonPath("$[0].updatedByName").value("CDG-132 User"))
				.andExpect(jsonPath("$[0].updatedAt").value("2026-10-02T00:00:00Z"))
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-OTHER-ARCHIVED')]").isEmpty())
				.andExpect(jsonPath("$.length()").value(1));
		list(unrelatedToken, "status", "ARCHIVED").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-ARCHIVED-STATUS')]").isEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-OTHER-ARCHIVED')]").isEmpty())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-HIDDEN-ARCHIVED"))
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void returnsStructuredBadRequestForUnsupportedStatusAndKeepsArrivalDateValidation() throws Exception {
		list(forwarderToken, "status", "banana").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.status").value("Status must be ACTIVE or ARCHIVED"));
		list(forwarderToken, "arrivalDate", "banana").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.arrivalDate").value("Arrival date must use ISO format YYYY-MM-DD"));
	}

	@Test
	void returnsStructuredBadRequestForUnknownSortAndNonBooleanAtRisk() throws Exception {
		list(forwarderToken, "sort", "newest").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.sort").value("Sort must be risk"));
		list(forwarderToken, "atRisk", "maybe").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.atRisk").value("atRisk must be true or false"));
		list(forwarderToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk());
		list(forwarderToken, "sort", "priority").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.sort").value("Sort must be risk"));
	}

	@Test
	void riskSortAndAtRiskFilterOnlyCoverShipmentsVisibleToTheActiveCompany() throws Exception {
		insert(forwarder, linkedImporter, "HBL-FWD-HIGH-LATE", "MV A", "MV F", "2026-10-13T09:00:00+08:00",
				"2026-10-01T00:00:00Z", 8);
		insert(forwarder, null, "HBL-FWD-HIGH-EARLY", "MV A", "MV F", "2026-10-12T09:00:00+08:00",
				"2026-10-02T00:00:00Z", 8);
		insert(forwarder, linkedImporter, "HBL-FWD-MEDIUM", "MV A", "MV F", "2026-10-12T09:00:00+08:00",
				"2026-10-03T00:00:00Z", 18);
		insert(forwarder, linkedImporter, "HBL-FWD-LOW", "MV A", "MV F", "2026-10-12T09:00:00+08:00",
				"2026-10-04T00:00:00Z", 48);
		insert(rivalForwarder, linkedImporter, "HBL-RIVAL-LINKED-HIGH", "MV A", "MV F", "2026-10-11T09:00:00+08:00",
				"2026-10-05T00:00:00Z", 8);
		insert(rivalForwarder, null, "HBL-RIVAL-HIGH", "MV A", "MV F", "2026-10-11T10:00:00+08:00",
				"2026-10-06T00:00:00Z", 8);
		insert(rivalForwarder, null, "HBL-RIVAL-MEDIUM", "MV A", "MV F", "2026-10-11T09:00:00+08:00",
				"2026-10-07T00:00:00Z", 18);
		insert(unrelated, null, "HBL-UNRELATED-HIGH", "MV A", "MV F", "2026-10-11T09:00:00+08:00",
				"2026-10-08T00:00:00Z", 8);
		String noShipmentsToken = tokenFor(account(otherImporter, "IMPORTER"));

		list(forwarderToken, "sort", "risk").andExpect(status().isOk())
				.andExpect(references("HBL-FWD-HIGH-EARLY", "HBL-FWD-HIGH-LATE", "HBL-FWD-MEDIUM", "HBL-FWD-LOW"));
		list(forwarderToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(references("HBL-FWD-HIGH-EARLY", "HBL-FWD-HIGH-LATE"))
				.andExpect(jsonPath("$[1].risk.level").value("HIGH"));
		list(forwarderToken, "sort", "risk", "atRisk", "true", "customer", linkedImporter.code())
				.andExpect(status().isOk())
				.andExpect(references("HBL-FWD-HIGH-LATE"));

		list(rivalForwarderToken, "sort", "risk").andExpect(status().isOk())
				.andExpect(references("HBL-RIVAL-LINKED-HIGH", "HBL-RIVAL-HIGH", "HBL-RIVAL-MEDIUM"));
		list(rivalForwarderToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(references("HBL-RIVAL-LINKED-HIGH", "HBL-RIVAL-HIGH"));

		list(linkedImporterToken, "sort", "risk").andExpect(status().isOk())
				.andExpect(references("HBL-RIVAL-LINKED-HIGH", "HBL-FWD-HIGH-LATE", "HBL-FWD-MEDIUM", "HBL-FWD-LOW"));
		list(linkedImporterToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(references("HBL-RIVAL-LINKED-HIGH", "HBL-FWD-HIGH-LATE"))
				.andExpect(jsonPath("$[0].importer.code").value(linkedImporter.code()))
				.andExpect(jsonPath("$[1].importer.code").value(linkedImporter.code()));

		list(unrelatedToken, "sort", "risk").andExpect(status().isOk())
				.andExpect(references("HBL-UNRELATED-HIGH"));
		list(unrelatedToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(references("HBL-UNRELATED-HIGH"));

		list(noShipmentsToken, "sort", "risk").andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
		list(noShipmentsToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
	}

	/** The listed shipment references, exactly and in this order. */
	private static org.springframework.test.web.servlet.ResultMatcher references(String... references) {
		return jsonPath("$[*].shipmentReference").value(org.hamcrest.Matchers.contains(references));
	}

	@Test
	void preservesUnauthenticatedListBehaviour() throws Exception {
		mvc.perform(get("/api/shipments")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	private org.springframework.test.web.servlet.ResultActions list(String token, String... parameters) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/shipments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		for (int index = 0; index < parameters.length; index += 2) {
			request.param(parameters[index], parameters[index + 1]);
		}
		return mvc.perform(request);
	}

	private CompanyFixture company(String name) {
		String code = "CDG132_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, name);
		Long id = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
		return new CompanyFixture(id, code);
	}

	private String account(CompanyFixture company, String role) {
		String email = "cdg132-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-132 User', ?, ?, id, ? FROM roles WHERE code = ?
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
		insert(managingCompany, importerCompany, reference, motherVessel, feederVessel, arrival, createdAt, 8);
	}

	private void insert(CompanyFixture managingCompany, CompanyFixture importerCompany, String reference,
			String motherVessel, String feederVessel, String arrival, String createdAt, int feederGapHours) {
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
				reference, motherVessel, plannedArrival, feederVessel, plannedArrival.plusHours(feederGapHours),
				Timestamp.from(Instant.parse(createdAt)), Timestamp.from(Instant.parse(createdAt)));
	}

	private record CompanyFixture(Long id, String code) {
	}

	private void archive(String reference) {
		jdbc.update("UPDATE shipments SET status = 'ARCHIVED' WHERE shipment_reference = ?", reference);
	}
}
