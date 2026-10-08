package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.drift.backend.shipment.exception.InvalidShipmentIdException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentAlreadyArchivedException;
import com.drift.backend.shipment.exception.ShipmentArchiveForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:postgresql://localhost:5432/drift_cdg81_20261008141600",
		"spring.flyway.url=jdbc:postgresql://localhost:5432/drift_cdg81_20261008141600",
		"spring.datasource.username=drift",
		"spring.datasource.password=drift",
		"spring.flyway.user=drift",
		"spring.flyway.password=drift",
		"app.ais.enabled=false"
})
@ContextConfiguration(initializers = ShipmentArchiveIntegrationTests.DisposableDatabaseGuard.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentArchiveIntegrationTests {

	static final String DATABASE = "drift_cdg81_20261008141600";
	static final String URL = "jdbc:postgresql://localhost:5432/" + DATABASE;

	static {
		try (Connection connection = DriverManager.getConnection(
				"jdbc:postgresql://localhost:5432/template1", "drift", "drift");
				Statement statement = connection.createStatement()) {
			boolean exists;
			try (ResultSet existing = statement.executeQuery(
					"SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'")) {
				exists = existing.next();
			}
			if (!exists) {
				statement.executeUpdate("CREATE DATABASE " + DATABASE);
			}
		} catch (SQLException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private String email;
	private Long companyId;
	private String token;

	@BeforeEach
	void account() throws Exception {
		email = "cdg81-" + UUID.randomUUID() + "@example.com";
		companyId = companyId("HARBOURLINE_DEMO");
		createAccount(email, companyId, "ADMIN");
		token = tokenFor(email);
	}

	@Test
	void archivesAShipmentKeepsTheRowAndRemovesItFromTheActiveList() throws Exception {
		Long archivedId = createShipment(token, "CDG81-ARCHIVED");
		Long activeId = createShipment(token, "CDG81-ACTIVE");
		assertThat(statusOf(archivedId)).isEqualTo(Shipment.ACTIVE);
		assertThat(statusOf(activeId)).isEqualTo(Shipment.ACTIVE);

		archive(token, archivedId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentService.REMOVED_MESSAGE));

		entityManager.flush();
		entityManager.clear();
		assertThat(statusOf(archivedId)).isEqualTo(Shipment.ARCHIVED);
		assertThat(statusOf(activeId)).isEqualTo(Shipment.ACTIVE);
		assertThat(countShipment(archivedId)).isEqualTo(1);

		list(token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + activeId + " && @.shipmentReference == 'CDG81-ACTIVE')]")
						.isNotEmpty())
				.andExpect(jsonPath("$[?(@.shipmentReference == 'CDG81-ARCHIVED')]").isEmpty());

		detail(token, archivedId.toString())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("CDG81-ARCHIVED"));

		archive(token, archivedId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(ShipmentAlreadyArchivedException.MESSAGE));
		entityManager.flush();
		entityManager.clear();
		assertThat(statusOf(archivedId)).isEqualTo(Shipment.ARCHIVED);
	}

	@Test
	void rejectsAnInvalidIdWithoutChangingTheExistingNotFoundResponse() throws Exception {
		archive(token, "not-a-number", "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidShipmentIdException.MESSAGE));

		mvc.perform(get("/api/shipments/not-a-number").header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		archive(token, "999999999", "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		archive(token, createShipment(token, "CDG81-STATUS").toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.status").value("Status must be ARCHIVED"));
	}

	@Test
	void letsALogisticsManagerArchiveAShipmentCreatedBySomeoneElse() throws Exception {
		String creatorEmail = "cdg81-creator-" + UUID.randomUUID() + "@example.com";
		createAccount(creatorEmail, companyId, "FREIGHT_FORWARDER");
		Long shipmentId = createShipment(tokenFor(creatorEmail), "CDG81-MANAGER");
		String managerEmail = "cdg81-manager-" + UUID.randomUUID() + "@example.com";
		createAccount(managerEmail, companyId, "LOGISTICS_MANAGER");

		archive(tokenFor(managerEmail), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ShipmentService.REMOVED_MESSAGE));
		entityManager.flush();
		entityManager.clear();
		assertThat(statusOf(shipmentId)).isEqualTo(Shipment.ARCHIVED);
	}

	@Test
	void rejectsFreightForwarderAndImporterCreatorsWithoutChangingTheShipment() throws Exception {
		String forwarderEmail = "cdg81-forwarder-" + UUID.randomUUID() + "@example.com";
		createAccount(forwarderEmail, companyId, "FREIGHT_FORWARDER");
		String forwarderToken = tokenFor(forwarderEmail);
		Long forwarderShipmentId = createShipment(forwarderToken, "CDG81-FORWARDER");

		String importerEmail = "cdg81-importer-" + UUID.randomUUID() + "@example.com";
		createAccount(importerEmail, companyId, "IMPORTER");
		String importerToken = tokenFor(importerEmail);
		Long importerShipmentId = createShipment(importerToken, "CDG81-IMPORTER");

		archive(forwarderToken, forwarderShipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentArchiveForbiddenException.MESSAGE));
		archive(importerToken, importerShipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentArchiveForbiddenException.MESSAGE));
		entityManager.flush();
		entityManager.clear();
		assertThat(statusOf(forwarderShipmentId)).isEqualTo(Shipment.ACTIVE);
		assertThat(statusOf(importerShipmentId)).isEqualTo(Shipment.ACTIVE);
		detail(forwarderToken, forwarderShipmentId.toString())
				.andExpect(status().isOk());
	}

	@Test
	void hidesAnotherCompanysShipmentAndRejectsAnInactiveCompany() throws Exception {
		Long ownShipmentId = createShipment(token, "CDG81-OWN");
		String otherAdminEmail = "cdg81-other-admin-" + UUID.randomUUID() + "@example.com";
		createAccount(otherAdminEmail, companyId("STRAITS_FRESH_DEMO"), "ADMIN");
		String otherManagerEmail = "cdg81-other-manager-" + UUID.randomUUID() + "@example.com";
		createAccount(otherManagerEmail, companyId("STRAITS_FRESH_DEMO"), "LOGISTICS_MANAGER");

		archive(tokenFor(otherAdminEmail), ownShipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		archive(tokenFor(otherManagerEmail), ownShipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		entityManager.flush();
		entityManager.clear();
		assertThat(statusOf(ownShipmentId)).isEqualTo(Shipment.ACTIVE);

		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", companyId);
		entityManager.clear();
		archive(token, ownShipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));
		entityManager.flush();
		entityManager.clear();
		assertThat(statusOf(ownShipmentId)).isEqualTo(Shipment.ACTIVE);
	}

	@Test
	void requiresABearerToken() throws Exception {
		mvc.perform(patch("/api/shipments/1/status")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ARCHIVED\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void documentsTheArchiveEndpoint() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}/status'].patch.summary")
						.value("Archive a shipment"))
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}/status'].patch.security[0].bearerAuth")
						.exists())
				.andExpect(jsonPath(
						"$.paths['/api/shipments/{shipmentId}/status'].patch.responses['200'].content['application/json'].example.message")
						.value(ShipmentService.REMOVED_MESSAGE))
				.andExpect(jsonPath(
						"$.paths['/api/shipments/{shipmentId}/status'].patch.responses['409'].content['application/json'].example.message")
						.value(ShipmentAlreadyArchivedException.MESSAGE))
				.andExpect(jsonPath(
						"$.paths['/api/shipments/{shipmentId}/status'].patch.responses['404'].content['application/json'].example.message")
						.value(ShipmentNotFoundException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}/status'].patch.responses['400']").exists())
				.andExpect(jsonPath(
						"$.paths['/api/shipments/{shipmentId}/status'].patch.responses['403'].content['application/json'].examples['Role'].value.message")
						.value(ShipmentArchiveForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.description")
						.value(org.hamcrest.Matchers.containsString("Archived shipments are excluded")));
	}

	@Test
	void appliesTheRoleMigrationAfterShipmentStatusWithoutChangingInvitations() {
		assertThat(jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
				.containsExactly("1", "2", "3", "4", "5", "6", "11", "12");
		String userRoles = jdbc.queryForObject(
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'users_role_check'", String.class);
		assertThat(userRoles).contains("ADMIN", "LOGISTICS_MANAGER", "FREIGHT_FORWARDER", "IMPORTER");
		String invitationRoles = jdbc.queryForObject(
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'invitations_role_check'", String.class);
		assertThat(invitationRoles).contains("IMPORTER", "FREIGHT_FORWARDER");
		assertThat(invitationRoles).doesNotContain("ADMIN").doesNotContain("LOGISTICS_MANAGER");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM pg_constraint WHERE conname = 'users_admin_has_company_check'", Integer.class))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'users_one_admin_per_company'", Integer.class))
				.isEqualTo(1);
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

	private ResultActions detail(String bearerToken, String shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + bearerToken));
	}

	private Long createShipment(String bearerToken, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
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
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						""".formatted(reference)))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		return id.longValue();
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

	private String statusOf(Long shipmentId) {
		return jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId);
	}

	private int countShipment(Long shipmentId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE id = ?", Integer.class, shipmentId);
	}

	static class DisposableDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext context) {
			String datasource = context.getEnvironment().getProperty("spring.datasource.url");
			String flyway = context.getEnvironment().getProperty("spring.flyway.url");
			if (datasource == null || flyway == null || !URL.equals(datasource) || !URL.equals(flyway)
					|| datasource.endsWith("/drift") || datasource.endsWith("/drift_test")
					|| datasource.contains("drift_conc") || datasource.contains("drift_cdg80")
					|| datasource.contains("drift_cdg107") || datasource.contains("drift_cdg81_20261008130500")) {
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
