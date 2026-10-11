package com.drift.backend.shipment.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import com.drift.backend.config.ShipmentImportTaskConfiguration;
import com.jayway.jsonpath.JsonPath;

/**
 * Exercises the real after-commit dispatcher while holding its task in a controlled queue.
 * This proves that HTTP acceptance persists a PENDING job before any row transaction starts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShipmentImportAsyncIntegrationTests.ControlledImportExecutor.class)
class ShipmentImportAsyncIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired @Qualifier(ShipmentImportTaskConfiguration.EXECUTOR_NAME) QueuedTaskExecutor executor;

	private Long forwarderCompanyId;
	private Long importerCompanyId;
	private String forwarderEmail;
	private String forwarderToken;
	private String importerToken;

	@BeforeEach
	void setUp() throws Exception {
		cleanupTestData();
		executor.clear();
		forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarderEmail = account("FREIGHT_FORWARDER", forwarderCompanyId);
		forwarderToken = tokenFor(forwarderEmail);
		importerToken = tokenFor(account("IMPORTER", importerCompanyId));
	}

	@AfterEach
	void cleanUp() {
		executor.clear();
		cleanupTestData();
	}

	@Test
	void persistsPendingFiveHundredAndOneRowJobBeforeReleaseThenCreatesLinkedShipments() throws Exception {
		createRelationship(forwarderCompanyId, importerCompanyId, true);
		StringBuilder source = new StringBuilder(header());
		for (int index = 0; index < 501; index++) {
			source.append('\n').append(validRow("HBL-CDG130-LARGE-" + index,
					index == 0 ? "STRAITS_FRESH_DEMO" : ""));
		}

		long jobId = upload(forwarderToken, source.toString());

		jobStatus(forwarderToken, jobId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.processedRows").value(0))
				.andExpect(jsonPath("$.importedCount").value(0));
		assertThat(executor.queuedTasks()).isOne();
		assertThat(jdbc.queryForObject("SELECT status FROM shipment_import_jobs WHERE id = ?", String.class, jobId))
				.isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE shipment_reference LIKE 'HBL-CDG130-LARGE-%'",
				Integer.class)).isZero();

		executor.runNext();

		jobStatus(forwarderToken, jobId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalRows").value(501))
				.andExpect(jsonPath("$.processedRows").value(501))
				.andExpect(jsonPath("$.importedCount").value(501))
				.andExpect(jsonPath("$.failedCount").value(0));
		assertThat(executor.queuedTasks()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE company_id = ? AND shipment_reference LIKE 'HBL-CDG130-LARGE-%'",
				Integer.class, forwarderCompanyId)).isEqualTo(501);
		Long shipmentId = jdbc.queryForObject("SELECT id FROM shipments WHERE shipment_reference = 'HBL-CDG130-LARGE-0'", Long.class);
		Long creatorId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, forwarderEmail);
		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isEqualTo(importerCompanyId);
		assertThat(jdbc.queryForObject("SELECT created_by_user_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isEqualTo(creatorId);
		assertThat(jdbc.queryForObject("SELECT updated_by_user_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isEqualTo(creatorId);

		mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shipmentReference").value("HBL-CDG130-LARGE-0"));
		mvc.perform(get("/api/shipments/" + shipmentId + "/tracking").header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isOk());
		mvc.perform(put("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + importerToken)
				.contentType(MediaType.APPLICATION_JSON).content(updateRequest("HBL-CDG130-LARGE-0", 0)))
				.andExpect(status().isForbidden());
	}

	@Test
	void reportsCsvAndDatabaseDuplicatesWithoutRollingBackADifferentCompanyReference() throws Exception {
		String existingReference = "HBL-CDG130-EXISTING";
		String otherCompanyReference = "HBL-CDG130-OTHER-COMPANY";
		createShipment(forwarderToken, existingReference);
		Long otherCompanyId = createCompany("CDG130OTHER");
		String otherToken = tokenFor(account("FREIGHT_FORWARDER", otherCompanyId));
		createShipment(otherToken, otherCompanyReference);

		long jobId = upload(forwarderToken, csv(
				validRow("HBL-CDG130-DUPLICATE", ""),
				validRow(" hbl-cdg130-duplicate ", ""),
				validRow(existingReference, ""),
				validRow(otherCompanyReference, "")));
		assertThat(executor.queuedTasks()).isOne();
		executor.runNext();

		jobStatus(forwarderToken, jobId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalRows").value(4))
				.andExpect(jsonPath("$.processedRows").value(4))
				.andExpect(jsonPath("$.importedCount").value(1))
				.andExpect(jsonPath("$.failedCount").value(3));
		mvc.perform(get("/api/shipments/import/" + jobId + "/errors").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'DUPLICATE_REFERENCE_IN_FILE')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.code == 'DUPLICATE_REFERENCE_IN_DATABASE')]").isNotEmpty());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_import_job_errors WHERE shipment_import_job_id = ? AND error_code = 'DUPLICATE_REFERENCE_IN_FILE'",
				Integer.class, jobId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_import_job_errors WHERE shipment_import_job_id = ? AND error_code = 'DUPLICATE_REFERENCE_IN_DATABASE'",
				Integer.class, jobId)).isOne();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE company_id = ? AND lower(shipment_reference) = lower(?)",
				Integer.class, forwarderCompanyId, "HBL-CDG130-DUPLICATE")).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE company_id = ? AND shipment_reference = ?",
				Integer.class, forwarderCompanyId, existingReference)).isOne();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE company_id = ? AND shipment_reference = ?",
				Integer.class, forwarderCompanyId, otherCompanyReference)).isOne();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE company_id = ? AND shipment_reference = ?",
				Integer.class, otherCompanyId, otherCompanyReference)).isOne();
	}

	private long upload(String token, String csv) throws Exception {
		MvcResult result = mvc.perform(multipart("/api/shipments/import").file(file(csv))
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isAccepted()).andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private void createShipment(String token, String reference) throws Exception {
		mvc.perform(post("/api/shipments").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(updateRequest(reference, 0)))
				.andExpect(status().isCreated());
	}

	private org.springframework.test.web.servlet.ResultActions jobStatus(String token, long jobId) throws Exception {
		return mvc.perform(get("/api/shipments/import/" + jobId).header("Authorization", "Bearer " + token));
	}

	private MockMultipartFile file(String csv) {
		return new MockMultipartFile("file", "shipments.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private String account(String role, Long companyId) {
		String email = "cdg130-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-130 Import User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany(String prefix) {
		String code = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, true)", code, "CDG-130 Other Company");
		return companyId(code);
	}

	private void createRelationship(Long forwarderId, Long importerId, boolean active) {
		Long actorId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, forwarderEmail);
		jdbc.update("""
				INSERT INTO forwarder_importer_relationships
				(forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
				VALUES (?, ?, ?, NOW(), ?, NOW(), ?)
				""", forwarderId, importerId, active, actorId, actorId);
	}

	private void cleanupTestData() {
		jdbc.update("DELETE FROM shipment_import_job_errors");
		jdbc.update("DELETE FROM shipment_import_jobs");
		jdbc.update("DELETE FROM shipments WHERE shipment_reference LIKE 'HBL-CDG130-%'");
		jdbc.update("DELETE FROM forwarder_importer_relationships WHERE created_by_user_id IN (SELECT id FROM users WHERE email LIKE 'cdg130-%')");
		jdbc.update("DELETE FROM users WHERE email LIKE 'cdg130-%'");
		jdbc.update("DELETE FROM companies WHERE code LIKE 'CDG130%'");
	}

	private static String csv(String... rows) {
		return header() + "\n" + String.join("\n", rows);
	}

	private static String header() {
		return String.join(",", ShipmentImportCsvContract.HEADERS);
	}

	private static String validRow(String reference, String importerCode) {
		return String.join(",", reference, "Singapore", "Rotterdam", "Tanjung Pelepas", "MV Mother",
				"2026-10-10T09:30:00+08:00", "MV Feeder", "2026-10-10T13:30:00+08:00", importerCode);
	}

	private static String updateRequest(String reference, long version) {
		return """
				{"shipmentReference":"%s","origin":"Singapore","destination":"Rotterdam","transshipmentPort":"Tanjung Pelepas",
				"motherVessel":"MV Mother","plannedMotherArrivalAt":"2026-10-10T09:30:00+08:00",
				"feederVessel":"MV Feeder","plannedFeederDepartureAt":"2026-10-10T13:30:00+08:00","version":%d}
				""".formatted(reference, version);
	}

	@TestConfiguration
	static class ControlledImportExecutor {
		@Bean(name = ShipmentImportTaskConfiguration.EXECUTOR_NAME)
		QueuedTaskExecutor shipmentImportTaskExecutor() {
			return new QueuedTaskExecutor();
		}
	}

	static final class QueuedTaskExecutor implements TaskExecutor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable task) {
			tasks.add(task);
		}

		int queuedTasks() {
			return tasks.size();
		}

		void runNext() {
			Runnable task = tasks.poll();
			assertThat(task).as("an after-commit import task should be queued").isNotNull();
			task.run();
		}

		void clear() {
			tasks.clear();
		}
	}
}
