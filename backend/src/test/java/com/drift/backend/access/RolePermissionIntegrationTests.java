package com.drift.backend.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.csvimport.BulkImportForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RolePermissionIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	@Test
	void enforcesTheRoleGateWithoutTakingShipmentWritesAwayFromALogisticsManager() throws Exception {
		String logistics = account("LOGISTICS_MANAGER", companyId("HARBOURLINE_DEMO"));
		String importer = account("IMPORTER", companyId("STRAITS_FRESH_DEMO"));
		String forwarder = account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO"));
		String admin = account("ADMIN", companyId("STRAITS_FRESH_DEMO"));
		String unassigned = account("FREIGHT_FORWARDER", null);

		mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token(importer)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		mvc.perform(get("/api/admin/roles").header("Authorization", "Bearer " + token(admin)))
				.andExpect(status().isOk());

		mvc.perform(multipart("/api/shipments/import")
				.file(new MockMultipartFile("file", "shipments.csv", "text/csv", "x".getBytes()))
				.header("Authorization", "Bearer " + token(logistics)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(BulkImportForbiddenException.ROLE));
		mvc.perform(post("/api/shipments").contentType(MediaType.APPLICATION_JSON).content(shipment("CDG110-LM"))
				.header("Authorization", "Bearer " + token(logistics)))
				.andExpect(status().isCreated());

		mvc.perform(patch("/api/shipments/999999/link-importer").contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":null}")
				.header("Authorization", "Bearer " + token(importer)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentLinkForbiddenException.MESSAGE));
		mvc.perform(patch("/api/shipments/999999/link-importer").contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":null}")
				.header("Authorization", "Bearer " + token(forwarder)))
				.andExpect(status().isNotFound());

		mvc.perform(post("/api/shipments").contentType(MediaType.APPLICATION_JSON).content(shipment("CDG110-NONE"))
				.header("Authorization", "Bearer " + token(unassigned)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentCreationForbiddenException.MESSAGE));
		mvc.perform(get("/api/shipments"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	private String shipment(String reference) {
		return """
				{"shipmentReference":"%s","origin":"Rotterdam","destination":"Jakarta","transshipmentPort":"Singapore","motherVessel":"Ever Steady","plannedMotherArrivalAt":"2026-10-15T08:00:00+08:00","feederVessel":"Straits Feeder","plannedFeederDepartureAt":"2026-10-16T12:00:00+08:00"}
				""".formatted(reference);
	}

	private String account(String role, Long companyId) {
		String email = "cdg110-" + UUID.randomUUID() + "@example.com";
		if (companyId == null) {
			jdbc.update("""
					INSERT INTO users (full_name, email, password_hash, role_id)
					SELECT 'Permission User', ?, ?, id FROM roles WHERE code = ?
					""", email, passwords.encode("Example123"), role);
		} else {
			jdbc.update("""
					INSERT INTO users (full_name, email, password_hash, role_id, company_id)
					SELECT 'Permission User', ?, ?, id, ? FROM roles WHERE code = ?
					""", email, passwords.encode("Example123"), companyId, role);
		}
		return email;
	}

	private String token(String email) throws Exception {
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
}
