package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.ForwarderImporterRelationshipRepository;
import com.drift.backend.company.TestAuthorisations;
import com.drift.backend.shipment.exception.UnauthorizedImporterLinkException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthorisedLinkConcurrencyIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired ForwarderImporterRelationshipRepository relationships;
	@Autowired UserAccountRepository users;

	@Test
	void aRevocationHoldingTheAuthorisationRejectsTheWaitingLink() throws Exception {
		String email = "cdg339-race-" + UUID.randomUUID() + "@example.com";
		Long forwarderId = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		Long importerId = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'", Long.class);
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 339', ?, ?, id, ?
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", email, passwords.encode("Example123"), forwarderId);
		Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		String token = token(email);
		Long shipmentId = createShipment(token);
		TestAuthorisations.allowShipment(jdbc, shipmentId, importerId);
		CountDownLatch locked = new CountDownLatch(1);
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);
		Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
			relationships.findByPairForUpdate(forwarderId, importerId).orElseThrow();
			locked.countDown();
			try {
				Thread.sleep(500);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			relationships.findByPairForUpdate(forwarderId, importerId).orElseThrow()
					.deactivate(users.findById(userId).orElseThrow());
		}));
		try {
			holder.start();
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
			mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"importerCompanyId\":" + importerId + "}"))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value(UnauthorizedImporterLinkException.MESSAGE));
			holder.join(10_000);
			assertThat(jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId))
					.isNull();
			assertThat(jdbc.queryForObject("""
					SELECT active FROM forwarder_importer_relationships
					WHERE forwarder_company_id = ? AND importer_company_id = ?
					""", Boolean.class, forwarderId, importerId)).isFalse();
		} finally {
			holder.interrupt();
			jdbc.update("DELETE FROM recommendation_decision_history WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM recommendation_decisions WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM shipment_importer_constraints WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM maritime_ai_assessments WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM shipment_risk_transitions WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM shipment_risk_states WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM shipment_change_events WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM shipments WHERE id = ?", shipmentId);
			jdbc.update("DELETE FROM forwarder_importer_relationships WHERE forwarder_company_id = ? AND importer_company_id = ?",
					forwarderId, importerId);
			jdbc.update("DELETE FROM users WHERE id = ?", userId);
		}
	}

	private String token(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private Long createShipment(String bearerToken) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "CDG339-%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						""".formatted(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		return id.longValue();
	}
}
