package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.exception.SessionEndedException;

import jakarta.persistence.EntityManager;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidShipmentIdException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentAlreadyArchivedException;
import com.drift.backend.shipment.exception.ShipmentArchiveForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:postgresql://localhost:5432/drift_cdg81int_20261011213000",
		"spring.flyway.url=jdbc:postgresql://localhost:5432/drift_cdg81int_20261011213000",
		"spring.datasource.username=drift",
		"spring.datasource.password=drift",
		"spring.flyway.user=drift",
		"spring.flyway.password=drift",
		"app.ais.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentArchiveIntegrationTests.DisposableDatabaseGuard.class)
@Transactional
class ShipmentArchiveIntegrationTests {

	private static final String DATABASE = "drift_cdg81int_20261011213000";
	private static final String URL = "jdbc:postgresql://localhost:5432/" + DATABASE;

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	@Test
	void archivesForAnOwningAdminAndKeepsTheRecordReadable() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String forwarderToken = tokenFor(forwarderEmail);
		Long shipmentId = createShipment(forwarderToken, "CDG81-ADMIN");
		Long importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		link(forwarderToken, shipmentId, importerCompanyId).andExpect(status().isOk());
		String importerEmail = email("importer");
		createAccount(importerEmail, "STRAITS_FRESH_DEMO", "IMPORTER");
		String importerToken = tokenFor(importerEmail);
		String adminEmail = email("admin");
		createAccount(adminEmail, "HARBOURLINE_DEMO", "ADMIN");
		String adminToken = tokenFor(adminEmail);
		Long activeId = createShipment(forwarderToken, "CDG81-STILL-ACTIVE");
		Row before = row(shipmentId);

		archive(adminToken, shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentService.REMOVED_MESSAGE));

		Row after = row(shipmentId);
		assertThat(after.status()).isEqualTo(Shipment.ARCHIVED);
		assertThat(after.reference()).isEqualTo(before.reference());
		assertThat(after.origin()).isEqualTo(before.origin());
		assertThat(after.destination()).isEqualTo(before.destination());
		assertThat(after.importerCompanyId()).isEqualTo(importerCompanyId);
		assertThat(after.updatedBy()).isEqualTo(accountId(adminEmail));
		assertThat(after.updatedAt()).isNotEqualTo(before.updatedAt());
		assertThat(after.version()).isEqualTo(before.version() + 1);
		assertThat(row(activeId).status()).isEqualTo(Shipment.ACTIVE);

		list(adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")]").isEmpty())
				.andExpect(jsonPath("$[?(@.id == " + activeId + ")]").isNotEmpty());
		list(importerToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")]").isEmpty());
		detail(adminToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("CDG81-ADMIN"));
		detail(importerToken, shipmentId).andExpect(status().isOk());
		mvc.perform(get("/api/shipments/" + shipmentId + "/tracking").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselName").value("MV Pacific Horizon"));
		mvc.perform(get("/api/shipments/" + shipmentId + "/tracking").header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isOk());

		archive(adminToken, shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isConflict())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentAlreadyArchivedException.MESSAGE));
		assertThat(row(shipmentId)).isEqualTo(after);
	}

	@Test
	void archivesForAnOwningLogisticsManager() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		Long shipmentId = createShipment(tokenFor(forwarderEmail), "CDG81-MANAGER");
		String managerEmail = email("manager");
		createAccount(managerEmail, "HARBOURLINE_DEMO", "LOGISTICS_MANAGER");
		Row before = row(shipmentId);

		archive(tokenFor(managerEmail), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ShipmentService.REMOVED_MESSAGE));

		Row after = row(shipmentId);
		assertThat(after.status()).isEqualTo(Shipment.ARCHIVED);
		assertThat(after.updatedBy()).isEqualTo(accountId(managerEmail));
		assertThat(after.version()).isEqualTo(before.version() + 1);
		assertThat(after.updatedAt()).isNotEqualTo(before.updatedAt());
	}

	@Test
	void rejectsSameCompanyForwarderAndImporterWithoutChangingTheRow() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String forwarderToken = tokenFor(forwarderEmail);
		Long shipmentId = createShipment(forwarderToken, "CDG81-FORWARDER");
		String importerEmail = email("importer");
		createAccount(importerEmail, "HARBOURLINE_DEMO", "IMPORTER");
		Long importerShipmentId = createShipment(tokenFor(importerEmail), "CDG81-IMPORTER");
		Row forwarderBefore = row(shipmentId);
		Row importerBefore = row(importerShipmentId);

		archive(forwarderToken, shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentArchiveForbiddenException.MESSAGE));
		archive(tokenFor(importerEmail), importerShipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentArchiveForbiddenException.MESSAGE));

		assertThat(row(shipmentId)).isEqualTo(forwarderBefore);
		assertThat(row(importerShipmentId)).isEqualTo(importerBefore);
	}

	@Test
	void reportsAnotherCompanyAndALinkedImporterAsNotFound() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String forwarderToken = tokenFor(forwarderEmail);
		Long shipmentId = createShipment(forwarderToken, "CDG81-OTHER");
		link(forwarderToken, shipmentId, companyId("STRAITS_FRESH_DEMO")).andExpect(status().isOk());
		String otherAdmin = email("other-admin");
		createAccount(otherAdmin, "STRAITS_FRESH_DEMO", "ADMIN");
		String otherManager = email("other-manager");
		createAccount(otherManager, "STRAITS_FRESH_DEMO", "LOGISTICS_MANAGER");
		String linkedImporter = email("linked-importer");
		createAccount(linkedImporter, "STRAITS_FRESH_DEMO", "IMPORTER");
		Row before = row(shipmentId);

		archive(tokenFor(otherAdmin), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		archive(tokenFor(otherManager), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		archive(tokenFor(linkedImporter), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		assertThat(row(shipmentId)).isEqualTo(before);
	}

	@Test
	void rejectsAnInactiveCompanyWithoutChangingTheRow() throws Exception {
		Long company = insertCompany("CDG81_INACTIVE", true);
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, company, "FREIGHT_FORWARDER");
		Long shipmentId = createShipment(tokenFor(forwarderEmail), "CDG81-INACTIVE");
		String adminEmail = email("admin");
		createAccount(adminEmail, company, "ADMIN");
		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", company);
		entityManager.clear();
		Row before = row(shipmentId);

		archive(tokenFor(adminEmail), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));

		assertThat(row(shipmentId)).isEqualTo(before);
	}

	@Test
	void rejectsMissingAuthenticationInvalidIdsAndInvalidStatus() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String token = tokenFor(forwarderEmail);
		Long shipmentId = createShipment(token, "CDG81-INVALID");

		mvc.perform(patch("/api/shipments/" + shipmentId + "/status")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ARCHIVED\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		archive(token, "not-a-number", "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidShipmentIdException.MESSAGE));
		mvc.perform(get("/api/shipments/not-a-number").header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		archive(token, "999999999", "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		archive(token, shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.status").value("Status must be ARCHIVED"));
		archive(token, shipmentId.toString(), "{\"status\":\"\"}")
				.andExpect(status().isBadRequest());

		assertThat(row(shipmentId).status()).isEqualTo(Shipment.ACTIVE);
	}

	@Test
	void keepsAnArchivedReferenceUnavailable() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String token = tokenFor(forwarderEmail);
		Long shipmentId = createShipment(token, "CDG81-REUSE");
		String adminEmail = email("admin");
		createAccount(adminEmail, "HARBOURLINE_DEMO", "ADMIN");
		archive(tokenFor(adminEmail), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());

		mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipmentJson("CDG81-REUSE")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(DuplicateShipmentReferenceException.MESSAGE));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM shipments WHERE company_id = ? AND shipment_reference = 'CDG81-REUSE'",
				Integer.class, companyId("HARBOURLINE_DEMO"))).isEqualTo(1);
		assertThat(row(shipmentId).status()).isEqualTo(Shipment.ARCHIVED);
	}

	@Test
	void rejectsEditsToAnArchivedShipmentAndStillEditsAnActiveShipment() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String token = tokenFor(forwarderEmail);
		Long archivedId = createShipment(token, "CDG81-NO-EDIT");
		Long activeId = createShipment(token, "CDG81-CAN-EDIT");
		String adminEmail = email("admin");
		createAccount(adminEmail, "HARBOURLINE_DEMO", "ADMIN");
		archive(tokenFor(adminEmail), archivedId.toString(), "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
		Row archived = row(archivedId);
		Row active = row(activeId);

		replace(token, archivedId, "CDG81-CHANGED", archived.version())
				.andExpect(status().isForbidden())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentNotEditableException.MESSAGE));
		assertThat(row(archivedId)).isEqualTo(archived);

		replace(token, activeId, "CDG81-EDITED", active.version())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("CDG81-EDITED"))
				.andExpect(jsonPath("$.version").value(active.version() + 1));
		Row edited = row(activeId);
		assertThat(edited.status()).isEqualTo(Shipment.ACTIVE);
		assertThat(edited.reference()).isEqualTo("CDG81-EDITED");
		assertThat(edited.updatedBy()).isEqualTo(accountId(forwarderEmail));
		assertThat(edited.version()).isEqualTo(active.version() + 1);
		assertThat(edited.updatedAt()).isNotEqualTo(active.updatedAt());
	}

	@Test
	void documentsArchiveAndAppliesOnlyTheMainMigrationSequence() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}/status'].patch.summary")
						.value("Archive a shipment"))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.description")
						.value(org.hamcrest.Matchers.containsString("Archived shipments are excluded")));

		assertThat(jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
				.containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "13", "14", "15", "16", "17", "18");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'role'",
				Integer.class)).isZero();
	}

	private ResultActions archive(String bearerToken, String shipmentId, String body) throws Exception {
		return mvc.perform(patch("/api/shipments/" + shipmentId + "/status")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions list(String bearerToken) throws Exception {
		return mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + bearerToken));
	}

	private ResultActions detail(String bearerToken, Long shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + bearerToken));
	}

	private ResultActions link(String bearerToken, Long shipmentId, Long importerCompanyId) throws Exception {
		return mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"));
	}

	private ResultActions replace(String bearerToken, Long shipmentId, String reference, long version) throws Exception {
		return mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00",
						  "version": %d
						}
						""".formatted(reference, version)));
	}

	private Long createShipment(String bearerToken, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipmentJson(reference)))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		assertThat(row(id.longValue()).status()).isEqualTo(Shipment.ACTIVE);
		return id.longValue();
	}

	private static String shipmentJson(String reference) {
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

	private void createAccount(String accountEmail, String companyCode, String role) {
		createAccount(accountEmail, companyId(companyCode), role);
	}

	private void createAccount(String accountEmail, Long accountCompanyId, String role) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Archive User', ?, ?, id, ?
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

	private Long accountId(String accountEmail) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, accountEmail);
	}

	private Long insertCompany(String code, boolean active) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, ?)", code, code, active);
		return companyId(code);
	}

	private Row row(Long shipmentId) {
		return jdbc.queryForObject("""
				SELECT shipment_reference, origin, destination, importer_company_id, status, version,
					updated_at::text, updated_by_user_id
				FROM shipments WHERE id = ?
				""", (result, rowNumber) -> new Row(
						result.getString(1),
						result.getString(2),
						result.getString(3),
						result.getObject(4) == null ? null : result.getLong(4),
						result.getString(5),
						result.getLong(6),
						result.getString(7),
						result.getLong(8)), shipmentId);
	}

	private static String email(String role) {
		return "cdg81-" + role + "-" + UUID.randomUUID() + "@example.com";
	}

	private record Row(String reference, String origin, String destination, Long importerCompanyId, String status,
			long version, String updatedAt, long updatedBy) {
	}

	static class DisposableDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext context) {
			String datasource = context.getEnvironment().getProperty("spring.datasource.url");
			String flyway = context.getEnvironment().getProperty("spring.flyway.url");
			if (!URL.equals(datasource) || !URL.equals(flyway)
					|| datasource.endsWith("/drift") || datasource.endsWith("/drift_test")
					|| datasource.contains("drift_cdg80") || datasource.contains("drift_cdg107")
					|| datasource.contains("drift_conc") || datasource.contains("drift_cdg81_20261008")) {
				throw new IllegalStateException("Refusing datasource " + datasource + " and Flyway " + flyway);
			}
			String ais = context.getEnvironment().getProperty("app.ais.enabled");
			if (!"false".equals(ais)) {
				throw new IllegalStateException("AIS must stay disabled, found " + ais);
			}
			try (Connection connection = DriverManager.getConnection(URL, "drift", "drift");
					Statement statement = connection.createStatement();
					ResultSet result = statement.executeQuery("SELECT current_database(), inet_server_port()")) {
				if (!result.next() || !DATABASE.equals(result.getString(1)) || result.getInt(2) != 5432) {
					throw new IllegalStateException("Connected database did not match " + DATABASE);
				}
			} catch (SQLException exception) {
				throw new IllegalStateException("Could not confirm the disposable database", exception);
			}
		}
	}
}
