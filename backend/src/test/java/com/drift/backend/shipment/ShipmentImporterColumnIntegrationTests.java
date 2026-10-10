package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.company.Company;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentImporterColumnIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private Long companyId;
	private String token;

	@BeforeEach
	void account() throws Exception {
		String email = "cdg119-" + UUID.randomUUID() + "@example.com";
		companyId = companyId("HARBOURLINE_DEMO");
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role, company_id)
				VALUES ('Alice Tan', ?, ?, 'FREIGHT_FORWARDER', ?)
				""", email, passwords.encode("Example123"), companyId);
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	@Test
	void storesANullableReferenceToAnotherCompanyAndIndexesIt() throws Exception {
		assertThat(jdbc.queryForMap("""
				SELECT is_nullable
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'shipments' AND column_name = 'importer_company_id'
				""")).containsEntry("is_nullable", "YES");
		assertThat(jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'shipments_importer_company_id_fkey'
				""", String.class)).contains("REFERENCES companies(id)");
		assertThat(jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'shipments_importer_is_another_company'
				""", String.class)).contains("importer_company_id <> company_id");
		assertThat(jdbc.queryForObject("""
				SELECT indexdef FROM pg_indexes WHERE indexname = 'shipments_importer_company_idx'
				""", String.class)).contains("importer_company_id");

		Long shipmentId = createShipment("HBL-119-OPEN");
		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isNull();

		Long importerId = companyId("STRAITS_FRESH_DEMO");
		Shipment shipment = entityManager.find(Shipment.class, shipmentId);
		shipment.linkImporter(entityManager.getReference(Company.class, importerId));
		entityManager.flush();
		entityManager.clear();

		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isEqualTo(importerId);
		assertThat(entityManager.find(Shipment.class, shipmentId).getImporterCompany().getId()).isEqualTo(importerId);

		entityManager.find(Shipment.class, shipmentId).linkImporter(null);
		entityManager.flush();
		assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
				.isNull();
	}

	@Test
	void rejectsAnImporterThatIsTheShipmentCompany() throws Exception {
		Long shipmentId = createShipment("HBL-119-SELF");
		Shipment shipment = entityManager.find(Shipment.class, shipmentId);
		shipment.linkImporter(entityManager.getReference(Company.class, companyId));

		assertThatThrownBy(() -> entityManager.flush())
				.isInstanceOf(ConstraintViolationException.class)
				.extracting(thrown -> ((ConstraintViolationException) thrown).getConstraintName())
				.isEqualTo("shipments_importer_is_another_company");
	}

	private Long createShipment(String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
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
		entityManager.flush();
		entityManager.clear();
		return id.longValue();
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
}
