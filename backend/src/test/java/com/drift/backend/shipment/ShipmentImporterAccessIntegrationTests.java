package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.csvimport.BulkImportAuthorizer;
import com.drift.backend.shipment.csvimport.BulkImportForbiddenException;
import com.drift.backend.shipment.csvimport.ImporterOrganisationAuthorizer;
import com.drift.backend.shipment.csvimport.ImporterOrganisationResolution;
import com.drift.backend.shipment.csvimport.ShipmentImportRow;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentImporterAccessIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;
	@Autowired UserAccountRepository users;
	@Autowired ImporterOrganisationAuthorizer importerAuthorizer;
	@Autowired BulkImportAuthorizer bulkImportAuthorizer;

	private Long forwarderCompanyId;
	private Long importerCompanyId;
	private Long unrelatedCompanyId;
	private String unrelatedCompanyCode;
	private String forwarderEmail;
	private String importerEmail;
	private String forwarderToken;
	private String importerToken;

	@BeforeEach
	void setUp() throws Exception {
		forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		unrelatedCompanyCode = "CDG129" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		unrelatedCompanyId = createCompany(unrelatedCompanyCode);
		forwarderEmail = account("FREIGHT_FORWARDER", forwarderCompanyId);
		importerEmail = account("IMPORTER", importerCompanyId);
		forwarderToken = tokenFor(forwarderEmail);
		importerToken = tokenFor(importerEmail);
	}

	@Test
	void importerManagedShipmentsRemainViewableTrackableAndEditableByTheirManagingImporter() throws Exception {
		Long shipmentId = create(importerToken, "HBL-IMPORTER-MANAGED");

		list(importerToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		get(importerToken, shipmentId).andExpect(status().isOk());
		tracking(importerToken, shipmentId).andExpect(status().isOk());
		update(importerToken, shipmentId, "HBL-IMPORTER-UPDATED", 0L).andExpect(status().isOk());
	}

	@Test
	void linkedImporterCanViewAndTrackButCannotUpdateWhileManagingForwarderRetainsAccess() throws Exception {
		Long shipmentId = create(forwarderToken, "HBL-FORWARDER-MANAGED");
		createRelationship(forwarderCompanyId, importerCompanyId, accountId(forwarderEmail), true);
		link(shipmentId, importerCompanyId);
		jdbc.update("UPDATE forwarder_importer_relationships SET active = FALSE WHERE forwarder_company_id = ? AND importer_company_id = ?",
				forwarderCompanyId, importerCompanyId);
		entityManager.clear();

		list(forwarderToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		get(forwarderToken, shipmentId).andExpect(status().isOk());
		tracking(forwarderToken, shipmentId).andExpect(status().isOk());
		update(forwarderToken, shipmentId, "HBL-FORWARDER-UPDATED", 0L).andExpect(status().isOk());

		list(importerToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		get(importerToken, shipmentId).andExpect(status().isOk());
		tracking(importerToken, shipmentId).andExpect(status().isOk());
		update(importerToken, shipmentId, "HBL-IMPROPER-UPDATE", 1L)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));

		String unrelatedToken = tokenFor(account("IMPORTER", unrelatedCompanyId));
		list(unrelatedToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		tracking(unrelatedToken, shipmentId).andExpect(status().isNotFound());
		update(unrelatedToken, shipmentId, "HBL-HIDDEN", 1L).andExpect(status().isNotFound());
	}

	@Test
	void activeRelationshipsAuthorizeExactCompanyCodesAndDeactivationOnlyBlocksNewLinks() {
		Long relationshipActorId = accountId(forwarderEmail);
		createRelationship(forwarderCompanyId, importerCompanyId, relationshipActorId, true);
		ShipmentImportRow row = row("STRAITS_FRESH_DEMO");

		ImporterOrganisationResolution resolved = importerAuthorizer.resolveAuthorizedImporter(
				entityManager.getReference(Company.class, forwarderCompanyId), row);
		assertThat(resolved.isAuthorized()).isTrue();
		assertThat(resolved.importerCompany().getId()).isEqualTo(importerCompanyId);
		assertThat(importerAuthorizer.resolveAuthorizedImporter(entityManager.getReference(Company.class, forwarderCompanyId),
				row(null)).importerCompany()).isNull();
		assertThat(importerAuthorizer.resolveAuthorizedImporter(entityManager.getReference(Company.class, forwarderCompanyId),
				row("straits_fresh_demo")).error().code()).isEqualTo("UNKNOWN_IMPORTER_ORGANISATION");
		assertThat(importerAuthorizer.resolveAuthorizedImporter(entityManager.getReference(Company.class, forwarderCompanyId),
				row("UNKNOWN")).error().code()).isEqualTo("UNKNOWN_IMPORTER_ORGANISATION");
		assertThat(importerAuthorizer.resolveAuthorizedImporter(entityManager.getReference(Company.class, forwarderCompanyId),
				row(unrelatedCompanyCode)).error().code()).isEqualTo("UNAUTHORIZED_IMPORTER_ORGANISATION");

		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", importerCompanyId);
		entityManager.clear();
		assertThat(importerAuthorizer.resolveAuthorizedImporter(entityManager.getReference(Company.class, forwarderCompanyId), row)
				.error().code()).isEqualTo("INACTIVE_IMPORTER_ORGANISATION");
		jdbc.update("UPDATE companies SET active = TRUE WHERE id = ?", importerCompanyId);
		entityManager.clear();

		jdbc.update("UPDATE forwarder_importer_relationships SET active = FALSE WHERE forwarder_company_id = ? AND importer_company_id = ?",
				forwarderCompanyId, importerCompanyId);
		assertThat(importerAuthorizer.resolveAuthorizedImporter(entityManager.getReference(Company.class, forwarderCompanyId), row)
				.error().code()).isEqualTo("UNAUTHORIZED_IMPORTER_ORGANISATION");

		assertThat(jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conname = 'forwarder_importer_relationships_different_companies'
				""", String.class)).contains("forwarder_company_id <> importer_company_id");
		assertThat(jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conname = 'forwarder_importer_relationships_unique_pair'
				""", String.class)).contains("UNIQUE (forwarder_company_id, importer_company_id)");
	}

	@Test
	void bulkImportAllowsOnlyFreightForwardersUntilCompanyTypeDefinesAdminScope() {
		bulkImportAuthorizer.authorize(account(forwarderEmail));
		assertThatThrownBy(() -> bulkImportAuthorizer.authorize(account(importerEmail)))
				.isInstanceOf(BulkImportForbiddenException.class);
		assertThatThrownBy(() -> bulkImportAuthorizer.authorize(account(account("LOGISTICS_MANAGER", forwarderCompanyId))))
				.isInstanceOf(BulkImportForbiddenException.class);
		assertThatThrownBy(() -> bulkImportAuthorizer.authorize(account(account("ADMIN", forwarderCompanyId))))
				.isInstanceOf(BulkImportForbiddenException.class)
				.hasMessageContaining("deferred");
	}

	private Long create(String token, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload(reference)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private org.springframework.test.web.servlet.ResultActions list(String token) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/shipments")
				.header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions get(String token, Long shipmentId) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions tracking(String token, Long shipmentId) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/shipments/" + shipmentId + "/tracking")
				.header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions update(String token, Long shipmentId, String reference, Long version)
			throws Exception {
		return mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload(reference, version)));
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
		String email = "cdg129-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-129 User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private UserAccount account(String email) {
		return users.findByEmailIgnoreCase(email).orElseThrow();
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany(String code) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-129 Other Company");
		return companyId(code);
	}

	private Long accountId(String email) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private void link(Long shipmentId, Long importerId) {
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerId, shipmentId);
		entityManager.clear();
	}

	private void createRelationship(Long forwarderId, Long importerId, Long actorId, boolean active) {
		jdbc.update("""
				INSERT INTO forwarder_importer_relationships (
					forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
				VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
				""", forwarderId, importerId, active, actorId, actorId);
	}

	private ShipmentImportRow row(String importerCode) {
		return new ShipmentImportRow(2, "HBL-CSV-129", "Singapore", "Rotterdam", "Tanjung Pelepas", "MV Mother",
				OffsetDateTime.parse("2026-10-10T09:30:00+08:00"), "MV Feeder",
				OffsetDateTime.parse("2026-10-10T15:45:00+08:00"), importerCode);
	}

	private static String payload(String reference) {
		return payload(reference, null);
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
