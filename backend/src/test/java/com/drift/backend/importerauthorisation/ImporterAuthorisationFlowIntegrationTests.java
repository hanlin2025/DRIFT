package com.drift.backend.importerauthorisation;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.drift.backend.config.ShipmentImportTaskConfiguration;
import com.drift.backend.shipment.csvimport.ImporterOrganisationAuthorizer;
import com.drift.backend.shipment.csvimport.ShipmentImportCsvContract;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.drift.backend.shipment.exception.UnauthorizedImporterLinkException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ImporterAuthorisationFlowIntegrationTests.InlineImport.class)
class ImporterAuthorisationFlowIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

	@AfterEach
	void cleanup() {
		List<Long> companies = jdbc.queryForList("SELECT id FROM companies WHERE code LIKE 'CDG341%'", Long.class);
		if (companies.isEmpty()) {
			return;
		}
		String ids = companies.toString().replace("[", "(").replace("]", ")");
		jdbc.update("DELETE FROM shipment_import_job_errors WHERE shipment_import_job_id IN (SELECT id FROM shipment_import_jobs WHERE managing_company_id IN " + ids + ")");
		jdbc.update("DELETE FROM shipment_import_jobs WHERE managing_company_id IN " + ids);
		jdbc.update("DELETE FROM recommendation_decision_history WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + ")");
		jdbc.update("DELETE FROM recommendation_decisions WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + ")");
		jdbc.update("DELETE FROM shipment_importer_constraints WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + ")");
		jdbc.update("DELETE FROM maritime_ai_assessments WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + ")");
		jdbc.update("DELETE FROM shipment_risk_alert_deliveries WHERE transition_id IN (SELECT id FROM shipment_risk_transitions WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + "))");
		jdbc.update("DELETE FROM shipment_risk_transitions WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + ")");
		jdbc.update("DELETE FROM shipment_risk_states WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + ")");
		jdbc.update("DELETE FROM shipment_change_events WHERE shipment_id IN (SELECT id FROM shipments WHERE company_id IN " + ids + ")");
		jdbc.update("DELETE FROM shipments WHERE company_id IN " + ids);
		jdbc.update("DELETE FROM forwarder_importer_relationships WHERE forwarder_company_id IN " + ids + " OR importer_company_id IN " + ids);
		jdbc.update("DELETE FROM users WHERE company_id IN " + ids);
		jdbc.update("DELETE FROM companies WHERE id IN " + ids);
	}

	@Test
	void grantLinkImportEndAndReauthoriseStayOnOneRow() throws Exception {
		Long forwarderId = company("F", "Forwarder " + suffix);
		Long importerId = company("I", "Importer " + suffix);
		Long outsiderId = company("O", "Outsider " + suffix);
		String importerCode = "CDG341" + suffix + "I";
		String admin = token(user(forwarderId, "ADMIN"));
		String forwarder = token(user(forwarderId, "FREIGHT_FORWARDER"));
		String importer = token(user(importerId, "IMPORTER"));
		String outsider = token(user(outsiderId, "ADMIN"));

		mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + forwarder)
				.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ImporterAuthorisationForbiddenException.CHANGE));
		mvc.perform(get("/api/importer-authorisations").header("Authorization", "Bearer " + outsider))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorisations", hasSize(0)));

		MvcResult granted = mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true))
				.andReturn();
		int authorisationId = JsonPath.read(granted.getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(authorisationId));
		mvc.perform(post("/api/importer-authorisations/" + authorisationId + "/end").header("Authorization", "Bearer " + outsider))
				.andExpect(status().isNotFound());

		MvcResult created = mvc.perform(post("/api/shipments").header("Authorization", "Bearer " + forwarder)
				.contentType(MediaType.APPLICATION_JSON).content(shipment("CDG341-LINK-" + suffix)))
				.andExpect(status().isCreated())
				.andReturn();
		int shipmentId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer").header("Authorization", "Bearer " + forwarder)
				.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importer.code").value(importerCode));

		long imported = upload(forwarder, "CDG341-IMP-" + suffix, importerCode);
		mvc.perform(get("/api/shipments/import/" + imported).header("Authorization", "Bearer " + forwarder))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importedCount").value(1))
				.andExpect(jsonPath("$.failedCount").value(0));

		mvc.perform(post("/api/importer-authorisations/" + authorisationId + "/end").header("Authorization", "Bearer " + admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
		mvc.perform(post("/api/importer-authorisations/" + authorisationId + "/end").header("Authorization", "Bearer " + admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer").header("Authorization", "Bearer " + forwarder)
				.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(UnauthorizedImporterLinkException.MESSAGE));
		long rejected = upload(forwarder, "CDG341-REJ-" + suffix, importerCode);
		mvc.perform(get("/api/shipments/import/" + rejected + "/errors").header("Authorization", "Bearer " + forwarder))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].code").value("UNAUTHORIZED_IMPORTER_ORGANISATION"))
				.andExpect(jsonPath("$[0].message").value(ImporterOrganisationAuthorizer.UNAUTHORIZED_MESSAGE));
		mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + importer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.importer.code").value(importerCode));

		mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + forwarderId + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidImporterOrganisationException.MESSAGE));
		mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(authorisationId))
				.andExpect(jsonPath("$.active").value(true));
		Integer rows = jdbc.queryForObject("""
				SELECT COUNT(*) FROM forwarder_importer_relationships
				WHERE forwarder_company_id = ? AND importer_company_id = ?
				""", Integer.class, forwarderId, importerId);
		org.junit.jupiter.api.Assertions.assertEquals(1, rows);
	}

	@Test
	void concurrentGrantsLeaveOneAuthorisation() throws Exception {
		Long forwarderId = company("C", "Concurrent " + suffix);
		Long importerId = company("D", "Concurrent importer " + suffix);
		String admin = token(user(forwarderId, "ADMIN"));
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new java.util.ArrayList<>();
			for (int attempt = 0; attempt < 4; attempt++) {
				results.add(pool.submit(() -> {
					start.await();
					MvcResult result = mvc.perform(post("/api/importer-authorisations").header("Authorization", "Bearer " + admin)
							.contentType(MediaType.APPLICATION_JSON).content("{\"importerCompanyId\":" + importerId + "}"))
							.andExpect(status().isOk())
							.andReturn();
					return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
				}));
			}
			start.countDown();
			int first = results.get(0).get();
			for (Future<Integer> result : results) {
				org.junit.jupiter.api.Assertions.assertEquals(first, result.get());
			}
		} finally {
			pool.shutdownNow();
		}
		Integer rows = jdbc.queryForObject("""
				SELECT COUNT(*) FROM forwarder_importer_relationships
				WHERE forwarder_company_id = ? AND importer_company_id = ? AND active = TRUE
				""", Integer.class, forwarderId, importerId);
		org.junit.jupiter.api.Assertions.assertEquals(1, rows);
	}

	private long upload(String token, String reference, String importerCode) throws Exception {
		String csv = String.join(",", ShipmentImportCsvContract.HEADERS) + "\n" + String.join(",",
				reference, "Singapore", "Rotterdam", "Tanjung Pelepas", "MV Mother",
				"2026-10-10T09:30:00+08:00", "MV Feeder", "2026-10-10T13:30:00+08:00", importerCode);
		MockMultipartFile file = new MockMultipartFile("file", "shipments.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
		MvcResult result = mvc.perform(multipart("/api/shipments/import").file(file).header("Authorization", "Bearer " + token))
				.andExpect(status().isAccepted())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String shipment(String reference) {
		return """
				{"shipmentReference":"%s","origin":"Singapore","destination":"Rotterdam","transshipmentPort":"Tanjung Pelepas","motherVessel":"MV Mother","plannedMotherArrivalAt":"2026-10-15T08:00:00+08:00","feederVessel":"MV Feeder","plannedFeederDepartureAt":"2026-10-15T17:00:00+08:00"}
				""".formatted(reference);
	}

	private Long company(String mark, String name) {
		String code = "CDG341" + suffix + mark;
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, name);
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private String user(Long companyId, String role) {
		String email = "cdg341-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 341', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private String token(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	@TestConfiguration
	static class InlineImport {
		@Bean(name = ShipmentImportTaskConfiguration.EXECUTOR_NAME)
		TaskExecutor shipmentImportTaskExecutor() {
			return new SyncTaskExecutor();
		}
	}
}
