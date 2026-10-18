package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.drift.backend.shipment.csvimport.ShipmentImportRow;
import com.drift.backend.shipment.csvimport.ShipmentImportShipmentCreator;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG81_STATUS_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG81_STATUS_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG81_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG81_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG81_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG81_DB_PASSWORD}",
		"app.ais.enabled=false",
		"app.jwt.secret=test-only-jwt-secret-not-used-outside-tests",
		"drift.startup-guard.expected-url=${DRIFT_CDG81_STATUS_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG81_STATUS_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = ShipmentStatusDatabaseGuard.class)
class ShipmentStatusContractTest {

	private static final String V21_SHA256 = "d1dfc6b2b861d716115a875f4796eda3db49e1a8725a3d5b496caf5d26cf1053";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired Environment environment;
	@Autowired ShipmentRepository shipments;
	@Autowired ShipmentImportShipmentCreator imports;

	private String email;
	private Long companyId;
	private Long accountId;
	private String token;

	@BeforeEach
	void account() throws Exception {
		email = "cdg80-status-" + UUID.randomUUID() + "@example.com";
		companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, "HARBOURLINE_DEMO");
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Alice Tan', ?, ?, id, ?
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", email, passwords.encode("Example123"), companyId);
		accountId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	@Test
	void validatesTheExistingStatusColumnAndInitializesNewShipmentsToActive() throws Exception {
		assertThat(environment.getActiveProfiles()).isEmpty();
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		assertThat(environment.getProperty("spring.datasource.url"))
				.isEqualTo(environment.getProperty("spring.flyway.url"))
				.matches(ShipmentStatusDatabaseGuard.URL.pattern());
		assertThat(environment.getProperty("app.ais.enabled")).isEqualTo("false");
		byte[] migration = getClass().getClassLoader().getResourceAsStream("db/migration/V21__add_shipment_status.sql")
				.readAllBytes();
		assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration))).isEqualTo(V21_SHA256);
		assertThat(new String(migration, StandardCharsets.UTF_8)).contains("VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'")
				.contains("CHECK (status IN ('ACTIVE', 'ARCHIVED'))");
		assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
				.containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "13", "14", "15", "16", "17", "19", "20", "21");
		assertThat(jdbc.queryForMap("""
				SELECT data_type, character_maximum_length, is_nullable, column_default
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'shipments' AND column_name = 'status'
				"""))
				.containsEntry("data_type", "character varying")
				.containsEntry("character_maximum_length", 16)
				.containsEntry("is_nullable", "NO");
		assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = 'shipments' AND column_name = 'status'",
				String.class)).contains("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'shipments_status_check'",
				String.class)).contains("ACTIVE").contains("ARCHIVED");
		assertThat(List.of(Shipment.class.getDeclaredMethods())).noneMatch(method -> method.getName().equals("setStatus"));
		assertThat(List.of(Shipment.class.getDeclaredMethods())).anyMatch(method -> method.getName().equals("archive"));
		String activeListQuery = ShipmentRepository.class
				.getMethod("findVisibleByCompanyIdOrderByCreatedAtDesc", Long.class)
				.getAnnotation(Query.class).value();
		assertThat(activeListQuery).contains("shipment.status = com.drift.backend.shipment.ShipmentStatus.ACTIVE")
				.doesNotContain("'ACTIVE'");
		assertThat(ShipmentRepository.class.getMethod("findByIdVisibleToCompanyId", Long.class, Long.class)
				.getAnnotation(Query.class).value()).doesNotContain("status");

		MvcResult created = create(shipment("HBL-API-ACTIVE", null));
		assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
		String createdJson = created.getResponse().getContentAsString();
		System.out.println("SAMPLE_LIST_ITEM " + createdJson);
		assertShipmentFields(createdJson, "HBL-API-ACTIVE", "ACTIVE", companyId);
		Long createdId = ((Number) JsonPath.read(createdJson, "$.id")).longValue();
		assertThat(storedStatus(createdId)).isEqualTo("ACTIVE");
		assertThat(shipments.findById(createdId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.ACTIVE);

		imports.create(companyId, accountId, new ShipmentImportRow(2, "HBL-CSV-ACTIVE", "Shanghai, CN", "Jakarta, ID",
				"Singapore", "MV Pacific Horizon", OffsetDateTime.parse("2026-10-15T08:00:00+08:00"), "MV Strait Runner",
				OffsetDateTime.parse("2026-10-16T12:00:00+08:00"), null), null);
		Long importedId = jdbc.queryForObject(
				"SELECT id FROM shipments WHERE shipment_reference = ? AND company_id = ?", Long.class, "HBL-CSV-ACTIVE",
				companyId);
		assertThat(storedStatus(importedId)).isEqualTo("ACTIVE");
		assertThat(shipments.findById(importedId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.ACTIVE);

		String detailJson = mvc.perform(get("/api/shipments/" + importedId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		System.out.println("SAMPLE_DETAIL " + detailJson);
		assertShipmentFields(detailJson, "HBL-CSV-ACTIVE", "ACTIVE", companyId);
		assertThat((Object) JsonPath.read(detailJson, "$.motherVesselPosition")).isNull();
		assertThat((Object) JsonPath.read(detailJson, "$.feederVesselPosition")).isNull();
		assertThat((Object) JsonPath.read(detailJson, "$.importer")).isNull();
		assertThat(JsonPath.read(detailJson, "$.risk.level").toString()).isEqualTo("LOW");
		assertThat(JsonPath.read(detailJson, "$.risk.explanation").toString())
				.isEqualTo("At least 24 hours to transfer cargo between vessels. The planned connection window is being used.");
		assertThat((Object) JsonPath.read(detailJson, "$.remainingConnectionWindow")).isNull();
		assertThatThrownBy(() -> JsonPath.read(createdJson, "$.risk")).isInstanceOf(PathNotFoundException.class);
		assertThatThrownBy(() -> JsonPath.read(createdJson, "$.remainingConnectionWindow")).isInstanceOf(PathNotFoundException.class);

		String listJson = mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(listJson, "$[?(@.shipmentReference == 'HBL-API-ACTIVE')].status"))
				.containsExactly("ACTIVE");
		assertThat((List<String>) JsonPath.read(listJson, "$[?(@.shipmentReference == 'HBL-CSV-ACTIVE')].status"))
				.containsExactly("ACTIVE");
	}

	@Test
	void ignoresStatusInCreateAndUpdatePayloads() throws Exception {
		MvcResult created = create(shipment("HBL-IGNORE-STATUS", "ARCHIVED"));
		assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
		String createdJson = created.getResponse().getContentAsString();
		Long shipmentId = ((Number) JsonPath.read(createdJson, "$.id")).longValue();
		Number version = JsonPath.read(createdJson, "$.version");
		assertThat(JsonPath.read(createdJson, "$.status").toString()).isEqualTo("ACTIVE");
		assertThat(storedStatus(shipmentId)).isEqualTo("ACTIVE");

		MvcResult updated = mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipment("HBL-IGNORE-STATUS", "ARCHIVED").replace("Jakarta, ID", "Surabaya, ID")
						.replaceFirst("\\{", "{\"version\":" + version + ",")))
				.andReturn();
		assertThat(updated.getResponse().getStatus()).as(updated.getResponse().getContentAsString()).isEqualTo(200);
		String updatedJson = updated.getResponse().getContentAsString();
		assertThat(JsonPath.read(updatedJson, "$.destination").toString()).isEqualTo("Surabaya, ID");
		assertThat(JsonPath.read(updatedJson, "$.status").toString()).isEqualTo("ACTIVE");
		assertThat(storedStatus(shipmentId)).isEqualTo("ACTIVE");
		assertThat(shipments.findById(shipmentId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.ACTIVE);
	}

	@Test
	void readsAnArchivedDatabaseValueAndOmitsItFromTheActiveList() throws Exception {
		MvcResult created = create(shipment("HBL-STILL-ACTIVE", null));
		assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
		Long activeId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
		Long archivedId = jdbc.queryForObject("""
				INSERT INTO shipments (
					company_id, created_by_user_id, updated_by_user_id, shipment_reference, origin, destination,
					transshipment_port, mother_vessel, planned_mother_arrival_at, feeder_vessel,
					planned_feeder_departure_at, created_at, updated_at, version, status)
				SELECT company_id, created_by_user_id, updated_by_user_id, 'HBL-ARCHIVED-FIXTURE', origin, destination,
					transshipment_port, mother_vessel, planned_mother_arrival_at, feeder_vessel,
					planned_feeder_departure_at, created_at, updated_at, version, 'ARCHIVED'
				FROM shipments WHERE id = ?
				RETURNING id
				""", Long.class, activeId);
		assertThat(storedStatus(activeId)).isEqualTo("ACTIVE");
		assertThat(storedStatus(archivedId)).isEqualTo("ARCHIVED");
		assertThat(shipments.findById(activeId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.ACTIVE);
		assertThat(shipments.findById(archivedId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.ARCHIVED);

		String detailJson = mvc.perform(get("/api/shipments/" + archivedId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-ARCHIVED-FIXTURE"))
				.andExpect(jsonPath("$.status").value("ARCHIVED"))
				.andExpect(jsonPath("$.origin").value("Shanghai, CN"))
				.andReturn().getResponse().getContentAsString();
		assertThat((Object) JsonPath.read(detailJson, "$.importer")).isNull();
		assertThat(JsonPath.read(detailJson, "$.status").toString()).isEqualTo("ARCHIVED");
		assertThat(JsonPath.read(detailJson, "$.risk.level").toString()).isEqualTo("LOW");
		assertThat(JsonPath.read(detailJson, "$.risk.explanation").toString())
				.isEqualTo("At least 24 hours to transfer cargo between vessels. The planned connection window is being used.");
		assertThat((Object) JsonPath.read(detailJson, "$.remainingConnectionWindow")).isNull();

		String listJson = mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(listJson, "$[?(@.shipmentReference == 'HBL-STILL-ACTIVE')].status"))
				.containsExactly("ACTIVE");
		assertThat((List<String>) JsonPath.read(listJson, "$[?(@.shipmentReference == 'HBL-ARCHIVED-FIXTURE')].status"))
				.isEmpty();
		String filteredJson = mvc.perform(get("/api/shipments").param("reference", "ARCHIVED-FIXTURE")
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(filteredJson, "$[?(@.shipmentReference == 'HBL-ARCHIVED-FIXTURE')].status"))
				.isEmpty();
		assertThat((List<?>) JsonPath.read(filteredJson, "$")).isEmpty();
		String activeFilterJson = mvc.perform(get("/api/shipments").param("reference", "HBL-STILL")
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(activeFilterJson, "$[?(@.shipmentReference == 'HBL-STILL-ACTIVE')].status"))
				.containsExactly("ACTIVE");

		mvc.perform(get("/api/shipments/" + archivedId + "/tracking").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselName").value("MV Pacific Horizon"))
				.andExpect(jsonPath("$.status").doesNotExist());
	}

	private MvcResult create(String body) throws Exception {
		return mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andReturn();
	}

	private String storedStatus(Long shipmentId) {
		return jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId);
	}

	private static void assertShipmentFields(String json, String reference, String status, Long companyId) {
		assertThat(((Number) JsonPath.read(json, "$.id")).longValue()).isPositive();
		assertThat(JsonPath.read(json, "$.shipmentReference").toString()).isEqualTo(reference);
		assertThat(JsonPath.read(json, "$.origin").toString()).isEqualTo("Shanghai, CN");
		assertThat(JsonPath.read(json, "$.destination").toString()).isEqualTo("Jakarta, ID");
		assertThat(JsonPath.read(json, "$.transshipmentPort").toString()).isEqualTo("Singapore");
		assertThat(JsonPath.read(json, "$.motherVessel").toString()).isEqualTo("MV Pacific Horizon");
		assertThat(JsonPath.read(json, "$.plannedMotherArrivalAt").toString()).isEqualTo("2026-10-15T00:00:00Z");
		assertThat(JsonPath.read(json, "$.feederVessel").toString()).isEqualTo("MV Strait Runner");
		assertThat(JsonPath.read(json, "$.plannedFeederDepartureAt").toString()).isEqualTo("2026-10-16T04:00:00Z");
		assertThat(JsonPath.read(json, "$.createdAt").toString()).isNotBlank();
		assertThat(((Number) JsonPath.read(json, "$.version")).longValue()).isZero();
		assertThat(JsonPath.read(json, "$.connectionWindow.duration").toString()).isEqualTo("1 day 4 hours");
		assertThat(((Number) JsonPath.read(json, "$.connectionWindow.totalSeconds")).longValue()).isEqualTo(100800L);
		assertThat(JsonPath.read(json, "$.status").toString()).isEqualTo(status);
		assertThat(((Number) JsonPath.read(json, "$.companyId")).longValue()).isEqualTo(companyId);
	}

	private static String shipment(String reference, String status) {
		String statusField = status == null ? "" : "\n  \"status\": \"%s\",".formatted(status);
		return """
				{%s
				  "shipmentReference": "%s",
				  "origin": "Shanghai, CN",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
				}
				""".formatted(statusField, reference);
	}
}
