package com.drift.backend.shipment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.InvalidShipmentFileException;
import com.drift.backend.shipment.exception.ShipmentImportForbiddenException;
import com.drift.backend.shipment.exception.ShipmentImportNotFoundException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShipmentImportIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private String forwarderToken;
	private String referencePrefix;

	@AfterEach
	void removeImportedRows() {
		jdbc.update("DELETE FROM shipment_import_errors");
		jdbc.update("DELETE FROM shipment_imports");
		jdbc.update("DELETE FROM shipments");
	}

	@BeforeEach
	void account() throws Exception {
		referencePrefix = "CDG26-" + UUID.randomUUID().toString().substring(0, 8);
		String email = "cdg26-" + UUID.randomUUID() + "@example.com";
		createAccount(email, companyId("HARBOURLINE_DEMO"), "FREIGHT_FORWARDER");
		forwarderToken = tokenFor(email);
	}

	@Test
	void importsValidRowsLinksTheImporterAndSkipsInvalidOnes() throws Exception {
		String csv = ShipmentCsv.template()
				+ row(referencePrefix + "-A", "Straits Fresh Imports (Demo)")
				+ row(referencePrefix + "-B", "")
				+ row(referencePrefix + "-A", "")
				+ row(referencePrefix + "-C", "Nobody Imports")
				+ "BAD,Shanghai,Jakarta,Singapore,MV Pacific Horizon,2026-10-16T12:00:00+08:00,MV Strait Runner,2026-10-15T08:00:00+08:00,\n";

		MvcResult imported = upload(forwarderToken, "shipments.csv", "text/csv", csv)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.imported").value(2))
				.andExpect(jsonPath("$.failed").value(3))
				.andExpect(jsonPath("$.summary").value("2 imported, 3 failed"))
				.andReturn();
		Number importId = JsonPath.read(imported.getResponse().getContentAsString(), "$.id");

		String straits = "cdg26-straits-" + UUID.randomUUID() + "@example.com";
		createAccount(straits, companyId("STRAITS_FRESH_DEMO"), "IMPORTER");
		mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + tokenFor(straits)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.shipmentReference == '" + referencePrefix + "-A')].importerOrganisation.name")
						.value(org.hamcrest.Matchers.contains("Straits Fresh Imports (Demo)")))
				.andExpect(jsonPath("$[?(@.shipmentReference == '" + referencePrefix + "-B')]").isEmpty());

		String report = mvc.perform(get("/api/shipment-imports/" + importId + "/errors")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"shipment-import-errors.csv\""))
				.andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(report)
				.contains("4,\"" + ShipmentCsv.DUPLICATE_REFERENCE + "\"")
				.contains("5,\"" + InvalidImporterOrganisationException.MESSAGE + "\"")
				.contains("6,\"" + InvalidItineraryException.MESSAGE + "\"");

		String other = "cdg26-other-" + UUID.randomUUID() + "@example.com";
		createAccount(other, companyId("STRAITS_FRESH_DEMO"), "FREIGHT_FORWARDER");
		mvc.perform(get("/api/shipment-imports/" + importId + "/errors")
				.header("Authorization", "Bearer " + tokenFor(other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentImportNotFoundException.MESSAGE));
	}

	@Test
	void reportsACleanImportAndHidesItFromAnImporter() throws Exception {
		upload(forwarderToken, "shipments.csv", "text/csv", ShipmentCsv.template() + row(referencePrefix + "-OK", "straits fresh imports (demo)"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.imported").value(1))
				.andExpect(jsonPath("$.failed").value(0))
				.andExpect(jsonPath("$.summary").value("1 shipment imported successfully"));

		String importer = "cdg26-imp-" + UUID.randomUUID() + "@example.com";
		createAccount(importer, companyId("HARBOURLINE_DEMO"), "IMPORTER");
		String importerToken = tokenFor(importer);
		mvc.perform(get("/api/shipment-imports/template").header("Authorization", "Bearer " + importerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentImportForbiddenException.MESSAGE));
		upload(importerToken, "shipments.csv", "text/csv", ShipmentCsv.template())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentImportForbiddenException.MESSAGE));

		mvc.perform(get("/api/shipment-imports/template").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/csv"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(ShipmentCsv.template()));
	}

	@Test
	void rejectsAFileThatIsNotTheTemplate() throws Exception {
		upload(forwarderToken, "notes.txt", "text/plain", "hello")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidShipmentFileException.MESSAGE));
		upload(forwarderToken, "shipments.csv", "text/csv", "Tracking/BL No.,Origin\nA,B\n")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidShipmentFileException.MESSAGE));
		upload(forwarderToken, "photo.png", "image/png", ShipmentCsv.template())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidShipmentFileException.MESSAGE));

		byte[] oversized = new byte[ShipmentCsv.MAX_BYTES + 1];
		mvc.perform(multipart("/api/shipment-imports")
				.file(new MockMultipartFile("file", "shipments.csv", "text/csv", oversized))
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidShipmentFileException.MESSAGE));

		mvc.perform(get("/api/shipment-imports/not-a-number/errors").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentImportNotFoundException.MESSAGE));
		mvc.perform(get("/api/shipments/" + referencePrefix).header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
	}

	@Test
	void documentsTheImportEndpoints() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/shipment-imports/template'].get.summary").value("Download the shipment CSV template"))
				.andExpect(jsonPath("$.paths['/api/shipment-imports'].post.summary").value("Import shipments from a CSV file"))
				.andExpect(jsonPath("$.paths['/api/shipment-imports/{importId}/errors'].get.summary").value("Download a shipment import error report"));
	}

	private org.springframework.test.web.servlet.ResultActions upload(String token, String filename, String contentType, String csv)
			throws Exception {
		return mvc.perform(multipart("/api/shipment-imports")
				.file(new MockMultipartFile("file", filename, contentType, csv.getBytes(StandardCharsets.UTF_8)))
				.header("Authorization", "Bearer " + token));
	}

	private static String row(String reference, String importer) {
		return reference + ",\"Shanghai, CN\",\"Jakarta, ID\",Singapore,MV Pacific Horizon,2026-10-15T08:00:00+08:00,MV Strait Runner,2026-10-16T12:00:00+08:00,"
				+ importer + "\n";
	}

	private void createAccount(String accountEmail, Long accountCompanyId, String role) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role, company_id)
				VALUES ('Alice Tan', ?, ?, ?, ?)
				""", accountEmail, passwords.encode("Example123"), role, accountCompanyId);
	}

	private String tokenFor(String accountEmail) throws Exception {
		MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(accountEmail)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
}
