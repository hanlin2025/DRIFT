package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.access.RolePermissionFilter;
import com.drift.backend.access.ShipmentVisibilityFilter;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG81_VISIBILITY_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG81_VISIBILITY_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG81_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG81_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG81_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG81_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG81_VISIBILITY_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG81_VISIBILITY_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentDisposableDatabaseGuard.class)
@Transactional
class ShipmentVisibilityIntegrationTests {

	private static final String DETAIL_FORBIDDEN = """
			{"message":"%s"}""".formatted(ShipmentDetailForbiddenException.MESSAGE).trim();
	private static final String TRACKING_HIDDEN = """
			{"message":"%s"}""".formatted(ShipmentNotFoundException.MESSAGE).trim();

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;
	@Autowired FilterChainProxy filters;

	private Long forwarderCompanyId;
	private Long importerCompanyId;
	private String forwarderToken;
	private String importerToken;

	@BeforeEach
	void setUp() throws Exception {
		forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarderToken = tokenFor(account("FREIGHT_FORWARDER", forwarderCompanyId));
		importerToken = tokenFor(account("IMPORTER", importerCompanyId));
	}

	@Test
	void registersTheVisibilityFilterAfterTheRoleGate() {
		SecurityFilterChain chain = filters.getFilterChains().get(0);
		var names = chain.getFilters().stream().map(filter -> filter.getClass().getName()).toList();
		int role = indexOf(names, RolePermissionFilter.class);
		int visibility = indexOf(names, ShipmentVisibilityFilter.class);
		assertThat(role).isGreaterThanOrEqualTo(0);
		assertThat(visibility).isGreaterThan(role);
	}

	@Test
	void linkedImporterCanReadButAnotherOrganisationReceivesNoShipmentDocument() throws Exception {
		Long shipmentId = create(forwarderToken, "HBL-CDG123-LINKED");
		link(shipmentId, importerCompanyId);
		String otherToken = tokenFor(account("IMPORTER", createCompany()));
		String managerToken = tokenFor(account("LOGISTICS_MANAGER", forwarderCompanyId));

		list(forwarderToken).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG123-LINKED')]").isNotEmpty());
		read(forwarderToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel").value("MV Mother"))
				.andExpect(jsonPath("$.importer.id").value(importerCompanyId));
		tracking(forwarderToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselName").value("MV Mother"));
		read(managerToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-CDG123-LINKED"));

		list(importerToken).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG123-LINKED')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.motherVessel == 'MV Mother')]").isNotEmpty());
		read(importerToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-CDG123-LINKED"));
		tracking(importerToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselName").value("MV Mother"));
		update(importerToken, shipmentId, "HBL-CDG123-DENIED", 0L)
				.andExpect(status().isForbidden())
				.andExpect(content().json("{\"message\":\"" + ShipmentDetailForbiddenException.MESSAGE + "\"}", true));

		list(otherToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		read(otherToken, shipmentId)
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().json(DETAIL_FORBIDDEN, true));
		tracking(otherToken, shipmentId)
				.andExpect(status().isNotFound())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().json(TRACKING_HIDDEN, true));
		update(otherToken, shipmentId, "HBL-CDG123-HIDDEN", 0L).andExpect(status().isNotFound());
	}

	@Test
	void importerWhoManagesAShipmentKeepsViewTrackingAndEdit() throws Exception {
		Long shipmentId = create(importerToken, "HBL-CDG123-MANAGED");

		list(importerToken).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG123-MANAGED')]").isNotEmpty());
		read(importerToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel").value("MV Mother"))
				.andExpect(jsonPath("$.importer").value(nullValue()));
		tracking(importerToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselName").value("MV Mother"));
		update(importerToken, shipmentId, "HBL-CDG123-UPDATED", 0L)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-CDG123-UPDATED"));
	}

	@Test
	void riskAndPrioritySortsAndAtRiskFilterNeverIncludeAnotherCompanysShipments() throws Exception {
		Long ownId = create(forwarderToken, "HBL-CDG189-OWN");
		link(ownId, importerCompanyId);
		String rivalToken = tokenFor(account("FREIGHT_FORWARDER", createCompany()));
		create(rivalToken, "HBL-CDG189-RIVAL");
		Long rivalLinkedId = create(rivalToken, "HBL-CDG189-RIVAL-LINKED");
		link(rivalLinkedId, importerCompanyId);
		String otherToken = tokenFor(account("IMPORTER", createCompany()));

		list(forwarderToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-OWN')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL')]").isEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL-LINKED')]").isEmpty());
		list(rivalToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL-LINKED')]").isNotEmpty())
				.andExpect(jsonPath("$.length()").value(2));
		list(importerToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-OWN')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL-LINKED')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL')]").isEmpty());
		list(otherToken, "sort", "risk", "atRisk", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		list(forwarderToken, "sort", "priority", "atRisk", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-OWN')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL')]").isEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL-LINKED')]").isEmpty());
		list(importerToken, "sort", "priority").andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'HBL-CDG189-RIVAL')]").isEmpty());
		list(otherToken, "sort", "priority").andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void listedRiskMatchesTheShipmentDetailRiskForEachViewer() throws Exception {
		Long highId = create(forwarderToken, "HBL-CDG189-HIGH");
		Long mediumId = create(forwarderToken, "HBL-CDG189-MEDIUM");
		Long lowId = create(forwarderToken, "HBL-CDG189-LOW");
		reschedule(mediumId, "2026-10-11T03:30:00+08:00");
		reschedule(lowId, "2026-10-12T09:30:00+08:00");
		for (Long shipmentId : java.util.List.of(highId, mediumId, lowId)) {
			link(shipmentId, importerCompanyId);
		}

		for (String token : java.util.List.of(forwarderToken, importerToken)) {
			String listed = list(token, "sort", "risk").andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			for (Long shipmentId : java.util.List.of(highId, mediumId, lowId)) {
				String detail = read(token, shipmentId).andExpect(status().isOk())
						.andReturn().getResponse().getContentAsString();
				String item = "$[?(@.id == %d)]".formatted(shipmentId);
				assertThat((java.util.List<Object>) JsonPath.read(listed, item + ".risk.level"))
						.containsExactly((Object) JsonPath.read(detail, "$.risk.level"));
				assertThat((java.util.List<Object>) JsonPath.read(listed, item + ".risk.explanation"))
						.containsExactly((Object) JsonPath.read(detail, "$.risk.explanation"));
				assertThat((java.util.List<Object>) JsonPath.read(listed, item + ".importer.id"))
						.containsExactly((Object) JsonPath.read(detail, "$.importer.id"));
				assertThat((java.util.List<Object>) JsonPath.read(listed, item + ".priority.score"))
						.containsExactly((Object) JsonPath.read(detail, "$.priority.score"));
			}
		}
		String detail = read(forwarderToken, mediumId).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(detail, "$.risk.level")).isEqualTo("MEDIUM");
		detail = read(forwarderToken, lowId).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(detail, "$.risk.level")).isEqualTo("LOW");
	}

	private static int indexOf(java.util.List<String> names, Class<?> type) {
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).contains(type.getName())) {
				return i;
			}
		}
		return -1;
	}

	private org.springframework.test.web.servlet.ResultActions list(String token, String... parameters) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/shipments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		for (int index = 0; index < parameters.length; index += 2) {
			request.param(parameters[index], parameters[index + 1]);
		}
		return mvc.perform(request);
	}

	private org.springframework.test.web.servlet.ResultActions read(String token, Long shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions tracking(String token, Long shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId + "/tracking")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions update(String token, Long shipmentId, String reference, Long version)
			throws Exception {
		return mvc.perform(put("/api/shipments/" + shipmentId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload(reference, version)));
	}

	private Long create(String token, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload(reference, null)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private String account(String role, Long companyId) {
		String email = "cdg123-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-123 User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany() {
		String code = "CDG123" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-123 Other Company");
		return companyId(code);
	}

	private void link(Long shipmentId, Long importerId) {
		entityManager.clear();
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerId, shipmentId);
		entityManager.clear();
	}

	private void reschedule(Long shipmentId, String plannedFeederDepartureAt) {
		entityManager.clear();
		jdbc.update("UPDATE shipments SET planned_feeder_departure_at = ? WHERE id = ?",
				java.time.OffsetDateTime.parse(plannedFeederDepartureAt), shipmentId);
		entityManager.clear();
	}

	private static String payload(String reference, Long version) {
		String versionField = version == null ? "" : ",\"version\":%s".formatted(version);
		return """
				{
				  "shipmentReference":"%s",
				  "origin":"Singapore",
				  "destination":"Rotterdam",
				  "transshipmentPort":"Tanjung Pelepas",
				  "motherVessel":"MV Mother",
				  "plannedMotherArrivalAt":"2026-10-10T09:30:00+08:00",
				  "feederVessel":"MV Feeder",
				  "plannedFeederDepartureAt":"2026-10-10T15:45:00+08:00"%s
				}
				""".formatted(reference, versionField);
	}
}
