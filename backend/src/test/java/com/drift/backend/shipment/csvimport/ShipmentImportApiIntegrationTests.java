package com.drift.backend.shipment.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShipmentImportApiIntegrationTests.DeterministicImportExecutor.class)
class ShipmentImportApiIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private Long forwarderCompanyId;
	private Long importerCompanyId;
	private String forwarderEmail;
	private String forwarderToken;
	private String importerToken;

	@BeforeEach
	void setUp() throws Exception {
		cleanupTestData();
		forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarderEmail = account("FREIGHT_FORWARDER", forwarderCompanyId);
		forwarderToken = tokenFor(forwarderEmail);
		importerToken = tokenFor(account("IMPORTER", importerCompanyId));
	}

	@AfterEach
	void cleanUp() {
		cleanupTestData();
	}

	private void cleanupTestData() {
		jdbc.update("DELETE FROM shipment_import_job_errors");
		jdbc.update("DELETE FROM shipment_import_jobs");
		jdbc.update("DELETE FROM shipments WHERE shipment_reference LIKE 'HBL-CDG126-%'");
		jdbc.update("""
				DELETE FROM forwarder_importer_relationships
				WHERE created_by_user_id IN (SELECT id FROM users WHERE email LIKE 'cdg126-%')
				""");
		jdbc.update("DELETE FROM users WHERE email LIKE 'cdg126-%'");
		jdbc.update("DELETE FROM companies WHERE code LIKE 'CDG126%'");
	}

	@Test
	void freightForwarderUploadCreatesACompletedJobAndPersistsShipments() throws Exception {
		long jobId = upload(forwarderToken, csv(validRow("HBL-CDG126-ONE", ""), validRow("HBL-CDG126-TWO", "")));

		jobStatus(forwarderToken, jobId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalRows").value(2))
				.andExpect(jsonPath("$.processedRows").value(2))
				.andExpect(jsonPath("$.importedCount").value(2))
				.andExpect(jsonPath("$.failedCount").value(0));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE company_id = ? AND shipment_reference LIKE 'HBL-CDG126-%'",
				Integer.class, forwarderCompanyId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE shipment_reference = 'HBL-CDG126-ONE' AND importer_company_id IS NULL",
				Integer.class)).isOne();
		assertThat(jdbc.queryForObject("SELECT source_csv IS NULL FROM shipment_import_jobs WHERE id = ?", Boolean.class, jobId))
				.isTrue();
	}

	@Test
	void recordsMultipleErrorsForOneRowButCountsThatRowOnceAndKeepsValidRows() throws Exception {
		String invalid = ",,Rotterdam,Tanjung Pelepas,MV Invalid,not-a-time,MV Feeder,2026-10-12T10:00:00+08:00,";
		long jobId = upload(forwarderToken, csv(validRow("HBL-CDG126-PARTIAL", ""), invalid));

		jobStatus(forwarderToken, jobId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalRows").value(2))
				.andExpect(jsonPath("$.processedRows").value(2))
				.andExpect(jsonPath("$.importedCount").value(1))
				.andExpect(jsonPath("$.failedCount").value(1));
		mvc.perform(get("/api/shipments/import/" + jobId + "/errors").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
				.andExpect(jsonPath("$[0].rowNumber").value(3));
	}

	@Test
	void fileLevelCsvFailureCreatesFailedJobAndNoShipment() throws Exception {
		long jobId = upload(forwarderToken, "Tracking/BL No.,Origin Port\nHBL-CDG126-BROKEN,Singapore\n");

		jobStatus(forwarderToken, jobId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failureMessage").value("CSV headers must exactly match the shipment import template"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE shipment_reference = 'HBL-CDG126-BROKEN'", Integer.class))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT source_csv IS NULL FROM shipment_import_jobs WHERE id = ?", Boolean.class, jobId))
				.isTrue();
	}

	@Test
	void enforcesUploadRolesEnvelopeAndManagingCompanyJobIsolation() throws Exception {
		MockMultipartFile file = file(csv(validRow("HBL-CDG126-AUTH", "")));
		mvc.perform(multipart("/api/shipments/import").file(file))
				.andExpect(status().isUnauthorized());
		mvc.perform(multipart("/api/shipments/import").file(file).header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isForbidden());
		String logisticsToken = tokenFor(account("LOGISTICS_MANAGER", forwarderCompanyId));
		mvc.perform(multipart("/api/shipments/import").file(file).header("Authorization", "Bearer " + logisticsToken))
				.andExpect(status().isForbidden());
		mvc.perform(multipart("/api/shipments/import").file(new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]))
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/shipments/import").file(new MockMultipartFile("file", "not-csv.txt", "text/plain", "x".getBytes()))
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest());

		long jobId = upload(forwarderToken, csv(validRow("HBL-CDG126-PRIVATE-JOB", "")));
		jobStatus(importerToken, jobId).andExpect(status().isNotFound());
		mvc.perform(get("/api/shipments/import/" + jobId + "/errors").header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isNotFound());
	}

	@Test
	void importsAuthorizedImporterLinksAndUsesTheSameJobPathForMoreThanFiveHundredRows() throws Exception {
		String linkedImporterCode = createCompany(true);
		Long linkedImporterId = companyId(linkedImporterCode);
		createRelationship(forwarderCompanyId, linkedImporterId, true);
		long linkedJob = upload(forwarderToken, csv(validRow("HBL-CDG126-LINKED", linkedImporterCode)));
		assertThat(jdbc.queryForObject("""
				SELECT importer_company_id FROM shipments WHERE shipment_reference = 'HBL-CDG126-LINKED'
				""", Long.class)).isEqualTo(linkedImporterId);

		StringBuilder largeCsv = new StringBuilder(header());
		for (int index = 0; index < 501; index++) {
			largeCsv.append('\n').append(validRow("HBL-CDG126-LARGE-" + index, ""));
		}
		long largeJob = upload(forwarderToken, largeCsv.toString());
		jobStatus(forwarderToken, largeJob)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalRows").value(501))
				.andExpect(jsonPath("$.processedRows").value(501))
				.andExpect(jsonPath("$.importedCount").value(501))
				.andExpect(jsonPath("$.failedCount").value(0));
	}

	@Test
	void convertsImporterResolutionFailuresAndExistingDuplicatesIntoRowFailures() throws Exception {
		String inactiveCode = createCompany(false);
		String missingRelationshipCode = createCompany(true);
		upload(forwarderToken, csv(validRow("HBL-CDG126-DUPLICATE", "")));
		long jobId = upload(forwarderToken, csv(
				validRow("HBL-CDG126-DUPLICATE", ""),
				validRow("HBL-CDG126-UNKNOWN", "UNKNOWN-CODE"),
				validRow("HBL-CDG126-INACTIVE", inactiveCode),
				validRow("HBL-CDG126-MISSING-REL", missingRelationshipCode)));

		jobStatus(forwarderToken, jobId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.importedCount").value(0))
				.andExpect(jsonPath("$.failedCount").value(4));
		mvc.perform(get("/api/shipments/import/" + jobId + "/errors").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'DUPLICATE_REFERENCE_IN_DATABASE')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.code == 'UNKNOWN_IMPORTER_ORGANISATION')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.code == 'INACTIVE_IMPORTER_ORGANISATION')]").isNotEmpty())
				.andExpect(jsonPath("$[?(@.code == 'UNAUTHORIZED_IMPORTER_ORGANISATION')]").isNotEmpty());
	}

	private long upload(String token, String csv) throws Exception {
		MvcResult result = mvc.perform(multipart("/api/shipments/import").file(file(csv))
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isAccepted())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private org.springframework.test.web.servlet.ResultActions jobStatus(String token, long jobId) throws Exception {
		return mvc.perform(get("/api/shipments/import/" + jobId).header("Authorization", "Bearer " + token));
	}

	private MockMultipartFile file(String csv) {
		return new MockMultipartFile("file", "shipments.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
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
		String email = "cdg126-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Import User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private void createRelationship(Long forwarderId, Long importerId, boolean active) {
		Long actorId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, forwarderEmail);
		jdbc.update("""
				INSERT INTO forwarder_importer_relationships
				(forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
				VALUES (?, ?, ?, NOW(), ?, NOW(), ?)
				""", forwarderId, importerId, active, actorId, actorId);
	}

	private String createCompany(boolean active) {
		String code = "CDG126" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, ?)", code, "CDG-126 Importer", active);
		return code;
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

	@TestConfiguration
	static class DeterministicImportExecutor {
		@Bean(name = ShipmentImportTaskConfiguration.EXECUTOR_NAME)
		TaskExecutor shipmentImportTaskExecutor() {
			return new SyncTaskExecutor();
		}
	}
}
