package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import com.drift.backend.shipment.exception.ShipmentAlreadyActiveException;
import com.drift.backend.shipment.exception.ShipmentAlreadyArchivedException;
import com.drift.backend.shipment.exception.ShipmentArchiveForbiddenException;
import com.drift.backend.shipment.exception.ShipmentRestoreForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG81_ARCHIVE_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG81_ARCHIVE_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG81_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG81_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG81_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG81_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG81_ARCHIVE_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG81_ARCHIVE_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentDisposableDatabaseGuard.class)
@Transactional
class ShipmentArchiveIntegrationTests {

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
		jdbc.update("UPDATE users SET full_name = 'Ada Admin' WHERE email = ?", adminEmail);
		String adminToken = tokenFor(adminEmail);
		Long activeId = createShipment(forwarderToken, "CDG81-STILL-ACTIVE");
		link(forwarderToken, activeId, importerCompanyId).andExpect(status().isOk());
		Row before = row(shipmentId);

		archive(adminToken, shipmentId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentService.REMOVED_MESSAGE));

		Row after = row(shipmentId);
		assertThat(after.status()).isEqualTo("ARCHIVED");
		assertThat(after.reference()).isEqualTo(before.reference());
		assertThat(after.origin()).isEqualTo(before.origin());
		assertThat(after.destination()).isEqualTo(before.destination());
		assertThat(after.importerCompanyId()).isEqualTo(importerCompanyId);
		assertThat(after.updatedBy()).isEqualTo(accountId(adminEmail));
		assertThat(after.updatedAt()).isNotEqualTo(before.updatedAt());
		assertThat(after.version()).isEqualTo(before.version() + 1);
		assertThat(row(activeId).status()).isEqualTo("ACTIVE");

		list(adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")]").isEmpty())
				.andExpect(jsonPath("$[?(@.id == " + activeId + ")].status")
						.value(org.hamcrest.Matchers.contains("ACTIVE")));
		list(importerToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")]").isEmpty())
				.andExpect(jsonPath("$[?(@.id == " + activeId + ")].status")
						.value(org.hamcrest.Matchers.contains("ACTIVE")));
		filteredList(adminToken, "CDG81-ADMIN")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")]").isEmpty())
				.andExpect(jsonPath("$.length()").value(0));
		filteredList(importerToken, "CDG81-ADMIN")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		filteredList(adminToken, "CDG81-STILL")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + activeId + ")].status")
						.value(org.hamcrest.Matchers.contains("ACTIVE")))
				.andExpect(jsonPath("$.length()").value(1));
		filteredList(importerToken, "CDG81-STILL")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/shipments").param("status", "ARCHIVED").param("reference", "CDG81-ADMIN")
				.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("ARCHIVED"))
				.andExpect(jsonPath("$[0].updatedByName").value("Ada Admin"));
		mvc.perform(get("/api/shipments").param("status", "ARCHIVED").param("reference", "CDG81-ADMIN")
				.header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].updatedByName").value("Ada Admin"));
		detail(adminToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("CDG81-ADMIN"))
				.andExpect(jsonPath("$.status").value("ARCHIVED"))
				.andExpect(jsonPath("$.updatedByName").doesNotExist())
				.andExpect(jsonPath("$.updatedAt").doesNotExist())
				.andExpect(jsonPath("$.risk.level").value("LOW"))
				.andExpect(jsonPath("$.remainingConnectionWindow").value(nullValue()))
				.andExpect(jsonPath("$.risk.explanation")
						.value("At least 24 hours to transfer cargo between vessels. The planned connection window is being used."))
				.andExpect(jsonPath("$.assessmentExplanation.evidence[0]").value("Planned connection window"))
				.andExpect(jsonPath("$.assessmentExplanation.evidence[1]").value("1 day 4 hours"))
				.andExpect(jsonPath("$.assessmentExplanation.evidence[2]").value("No AIS position has been received for the mother vessel."))
				.andExpect(jsonPath("$.assessmentExplanation.confidence")
						.value("Confidence is limited because the planned connection window is being used."))
				.andExpect(jsonPath("$.assessmentExplanation.unavailable").value(nullValue()));
		detail(importerToken, shipmentId).andExpect(status().isOk())
				.andExpect(jsonPath("$.assessmentExplanation.confidence")
						.value("Confidence is limited because the planned connection window is being used."))
				.andExpect(jsonPath("$.assessmentExplanation.evidence[1]").value("1 day 4 hours"));
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
		assertThat(after.status()).isEqualTo("ARCHIVED");
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
		archive(token, shipmentId.toString(), "{\"status\":\"REMOVED\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.status").value("Status must be ACTIVE or ARCHIVED"));
		archive(token, shipmentId.toString(), "{\"status\":\"\"}")
				.andExpect(status().isBadRequest());

		assertThat(row(shipmentId).status()).isEqualTo("ACTIVE");
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
		assertThat(row(shipmentId).status()).isEqualTo("ARCHIVED");
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
		assertThat(edited.status()).isEqualTo("ACTIVE");
		assertThat(edited.reference()).isEqualTo("CDG81-EDITED");
		assertThat(edited.updatedBy()).isEqualTo(accountId(forwarderEmail));
		assertThat(edited.version()).isEqualTo(active.version() + 1);
		assertThat(edited.updatedAt()).isNotEqualTo(active.updatedAt());
	}

	@Test
	void archivePreservesVesselIdentityAndAnOmittedUpdateKeepsIt() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String token = tokenFor(forwarderEmail);
		Long archivedId = createShipmentWithIdentity(token, "CDG81-IDENTITY");
		Map<String, Object> archivedIdentity = identity(archivedId);
		String adminEmail = email("admin");
		createAccount(adminEmail, "HARBOURLINE_DEMO", "ADMIN");

		archive(tokenFor(adminEmail), archivedId.toString(), "{\"status\":\"ARCHIVED\"}")
				.andExpect(status().isOk());

		assertThat(identity(archivedId)).isEqualTo(archivedIdentity);
		assertThat(row(archivedId).status()).isEqualTo("ARCHIVED");
		assertThat(row(archivedId).reference()).isEqualTo("CDG81-IDENTITY");
		assertThat(row(archivedId).destination()).isEqualTo("Jakarta, ID");

		Long activeId = createShipmentWithIdentity(token, "CDG81-KEEP-IDENTITY");
		Map<String, Object> stored = identity(activeId);
		replace(token, activeId, "CDG81-KEPT-IDENTITY", row(activeId).version())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shipmentReference").value("CDG81-KEPT-IDENTITY"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertThat(identity(activeId)).isEqualTo(stored);
		assertThat(row(activeId).destination()).isEqualTo("Jakarta, ID");
	}

	@Test
	void restoresAnArchivedShipmentWithoutChangingItsOtherDetails() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String forwarderToken = tokenFor(forwarderEmail);
		Long shipmentId = createShipmentWithIdentity(forwarderToken, "CDG81-RESTORE");
		Long importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		link(forwarderToken, shipmentId, importerCompanyId).andExpect(status().isOk());
		String importerEmail = email("importer");
		createAccount(importerEmail, "STRAITS_FRESH_DEMO", "IMPORTER");
		String importerToken = tokenFor(importerEmail);
		String adminEmail = email("admin");
		createAccount(adminEmail, "HARBOURLINE_DEMO", "ADMIN");
		jdbc.update("UPDATE users SET full_name = 'Ada Admin' WHERE email = ?", adminEmail);
		String adminToken = tokenFor(adminEmail);
		archive(adminToken, shipmentId.toString(), "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
		Map<String, Object> storedIdentity = identity(shipmentId);
		Row archived = row(shipmentId);

		archive(adminToken, shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentService.RESTORED_MESSAGE));

		Row restored = row(shipmentId);
		assertThat(restored.status()).isEqualTo("ACTIVE");
		assertThat(restored.reference()).isEqualTo(archived.reference());
		assertThat(restored.origin()).isEqualTo(archived.origin());
		assertThat(restored.destination()).isEqualTo(archived.destination());
		assertThat(restored.importerCompanyId()).isEqualTo(importerCompanyId);
		assertThat(restored.updatedBy()).isEqualTo(accountId(adminEmail));
		assertThat(restored.updatedAt()).isNotEqualTo(archived.updatedAt());
		assertThat(restored.version()).isEqualTo(archived.version() + 1);
		assertThat(identity(shipmentId)).isEqualTo(storedIdentity);
		list(adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")]").isNotEmpty());
		list(importerToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")]").isNotEmpty());
		mvc.perform(get("/api/shipments").param("status", "ARCHIVED").param("reference", "CDG81-RESTORE")
				.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		archive(adminToken, shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isConflict())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentAlreadyActiveException.MESSAGE));
		assertThat(row(shipmentId)).isEqualTo(restored);
	}

	@Test
	void restoresForAnOwningLogisticsManager() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		Long shipmentId = createShipment(tokenFor(forwarderEmail), "CDG81-MANAGER-RESTORE");
		String managerEmail = email("manager");
		createAccount(managerEmail, "HARBOURLINE_DEMO", "LOGISTICS_MANAGER");
		String managerToken = tokenFor(managerEmail);
		archive(managerToken, shipmentId.toString(), "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
		Row archived = row(shipmentId);

		archive(managerToken, shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ShipmentService.RESTORED_MESSAGE));

		Row restored = row(shipmentId);
		assertThat(restored.status()).isEqualTo("ACTIVE");
		assertThat(restored.updatedBy()).isEqualTo(accountId(managerEmail));
		assertThat(restored.version()).isEqualTo(archived.version() + 1);
		assertThat(restored.reference()).isEqualTo(archived.reference());
		assertThat(restored.destination()).isEqualTo(archived.destination());
	}

	@Test
	void rejectsRestoreFromARoleThatCannotChangeStatusWithoutChangingTheRow() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String forwarderToken = tokenFor(forwarderEmail);
		Long shipmentId = createShipment(forwarderToken, "CDG81-NO-RESTORE");
		String adminEmail = email("admin");
		createAccount(adminEmail, "HARBOURLINE_DEMO", "ADMIN");
		archive(tokenFor(adminEmail), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
		String importerEmail = email("importer");
		createAccount(importerEmail, "HARBOURLINE_DEMO", "IMPORTER");
		Row archived = row(shipmentId);

		archive(forwarderToken, shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isForbidden())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentRestoreForbiddenException.MESSAGE));
		archive(tokenFor(importerEmail), shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentRestoreForbiddenException.MESSAGE));

		assertThat(row(shipmentId)).isEqualTo(archived);
	}

	@Test
	void reportsRestoreOfAnotherCompanyAndALinkedImporterAsNotFound() throws Exception {
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, "HARBOURLINE_DEMO", "FREIGHT_FORWARDER");
		String forwarderToken = tokenFor(forwarderEmail);
		Long shipmentId = createShipment(forwarderToken, "CDG81-OTHER-RESTORE");
		link(forwarderToken, shipmentId, companyId("STRAITS_FRESH_DEMO")).andExpect(status().isOk());
		String adminEmail = email("admin");
		createAccount(adminEmail, "HARBOURLINE_DEMO", "ADMIN");
		archive(tokenFor(adminEmail), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
		String otherAdmin = email("other-admin");
		createAccount(otherAdmin, "STRAITS_FRESH_DEMO", "ADMIN");
		String linkedImporter = email("linked-importer");
		createAccount(linkedImporter, "STRAITS_FRESH_DEMO", "IMPORTER");
		Row archived = row(shipmentId);

		archive(tokenFor(otherAdmin), shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		archive(tokenFor(linkedImporter), shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		assertThat(row(shipmentId)).isEqualTo(archived);
	}

	@Test
	void rejectsRestoreFromAnInactiveCompanyWithoutChangingTheRow() throws Exception {
		Long company = insertCompany("CDG81_INACTIVE_RESTORE", true);
		String forwarderEmail = email("forwarder");
		createAccount(forwarderEmail, company, "FREIGHT_FORWARDER");
		Long shipmentId = createShipment(tokenFor(forwarderEmail), "CDG81-INACTIVE-RESTORE");
		String adminEmail = email("admin");
		createAccount(adminEmail, company, "ADMIN");
		archive(tokenFor(adminEmail), shipmentId.toString(), "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", company);
		entityManager.clear();
		Row archived = row(shipmentId);

		archive(tokenFor(adminEmail), shipmentId.toString(), "{\"status\":\"ACTIVE\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));

		assertThat(row(shipmentId)).isEqualTo(archived);
	}

	@Test
	void documentsArchiveAndAppliesOnlyTheMainMigrationSequence() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}/status'].patch.summary")
						.value("Archive or restore a shipment"))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.description")
						.value(org.hamcrest.Matchers.containsString("Archived shipments are excluded")));

		assertThat(jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
				.containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "13", "14", "15", "16", "17", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29");
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

	private ResultActions filteredList(String bearerToken, String reference) throws Exception {
		return mvc.perform(get("/api/shipments").param("reference", reference)
				.header("Authorization", "Bearer " + bearerToken));
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

	private Long createShipmentWithIdentity(String bearerToken, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipmentJson(reference).replace("\n}", """
						,
						  "motherVesselImo": "9387421",
						  "motherVesselMmsi": "563000111",
						  "motherVesselOperator": "Harbourline",
						  "feederVesselImo": "9411882",
						  "feederVesselMmsi": "565987654",
						  "feederVesselOperator": "Strait Feeders"
						}
						""")))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		assertThat(identity(id.longValue()))
				.containsEntry("mother_vessel_imo", "9387421")
				.containsEntry("mother_vessel_mmsi", "563000111")
				.containsEntry("mother_vessel_operator", "Harbourline")
				.containsEntry("feeder_vessel_imo", "9411882")
				.containsEntry("feeder_vessel_mmsi", "565987654")
				.containsEntry("feeder_vessel_operator", "Strait Feeders");
		return id.longValue();
	}

	private Map<String, Object> identity(Long shipmentId) {
		return jdbc.queryForMap("""
				SELECT mother_vessel_imo, mother_vessel_mmsi, mother_vessel_operator,
					feeder_vessel_imo, feeder_vessel_mmsi, feeder_vessel_operator
				FROM shipments WHERE id = ?
				""", shipmentId);
	}

	private Long createShipment(String bearerToken, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipmentJson(reference)))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		assertThat(row(id.longValue()).status()).isEqualTo("ACTIVE");
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
}
