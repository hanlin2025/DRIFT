package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.drift.backend.shipment.exception.RecommendationDecisionRejectedException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecommendationDecisionDelayComparisonIntegrationTests {

	private static final BigDecimal LATITUDE = new BigDecimal("1.26");
	private static final BigDecimal LONGITUDE = new BigDecimal("103.82");
	private static final BigDecimal SPEED = new BigDecimal("12.5");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private String forwarderToken;
	private String importerToken;
	private long importerCompanyId;

	@BeforeEach
	void accounts() throws Exception {
		long forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarderToken = tokenFor(account("FREIGHT_FORWARDER", forwarderCompanyId));
		importerToken = tokenFor(account("IMPORTER", importerCompanyId));
	}

	@Test
	void aCompletedComparisonCanBeDecidedAndAStaleOrIncompatibleOneCannot() throws Exception {
		String vessel = "MV CDG53 " + UUID.randomUUID().toString().substring(0, 8);
		long shipmentId = createShipment(vessel);
		link(shipmentId);
		observe(vessel, Instant.now().truncatedTo(ChronoUnit.MICROS), "563000111");
		saveDeadline("2099-01-01T00:00:00Z");

		mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.delayComparison.outcome").value("NO_PREDICTED_IMPACT"))
				.andExpect(jsonPath("$.recommendation.available").value(true))
				.andExpect(jsonPath("$.recommendation.impact.outcome").value("NO_PREDICTED_IMPACT"))
				.andExpect(jsonPath("$.recommendation.impact.detail").value(nullValue()));

		mvc.perform(patch("/api/shipments/" + shipmentId + "/recommendation-decision")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"APPROVED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("APPROVED"));
		assertThat(count("recommendation_decisions", shipmentId)).isEqualTo(1);
		assertThat(count("recommendation_decision_history", shipmentId)).isEqualTo(1);

		long staleId = createShipment(vessel + " stale");
		link(staleId);
		observe(vessel + " stale", Instant.now().minus(7, ChronoUnit.HOURS), "563000222");
		saveDeadline(staleId, "2099-01-01T00:00:00Z");
		mvc.perform(get("/api/shipments/" + staleId).header("Authorization", "Bearer " + forwarderToken))
				.andExpect(jsonPath("$.delayComparison.reason").value("STALE_PREDICTION"))
				.andExpect(jsonPath("$.recommendation.available").value(false))
				.andExpect(jsonPath("$.recommendation.impact.detail").value(DelayComparison.STALE));
		mvc.perform(patch("/api/shipments/" + staleId + "/recommendation-decision")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"APPROVED\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(RecommendationDecisionRejectedException.UNAVAILABLE));
		assertThat(count("recommendation_decisions", staleId)).isZero();
		assertThat(count("recommendation_decision_history", staleId)).isZero();

		long incompatibleId = createShipment(vessel + " port");
		link(incompatibleId);
		observe(vessel + " port", Instant.now().truncatedTo(ChronoUnit.MICROS), "563000333");
		saveDeadline(incompatibleId, "2099-01-01T00:00:00Z");
		replacePort(incompatibleId, "Tanjung Pelepas");
		mvc.perform(get("/api/shipments/" + incompatibleId).header("Authorization", "Bearer " + forwarderToken))
				.andExpect(jsonPath("$.delayComparison.reason").value("INCOMPATIBLE_MILESTONE"))
				.andExpect(jsonPath("$.recommendation.impact.detail").value(DelayComparison.INCOMPATIBLE));
		mvc.perform(patch("/api/shipments/" + incompatibleId + "/recommendation-decision")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"APPROVED\"}"))
				.andExpect(status().isConflict());
		assertThat(count("recommendation_decisions", incompatibleId)).isZero();
	}

	private long createShipment(String vessel) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "%s",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						""".formatted("CDG53-" + UUID.randomUUID(), vessel)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private void link(long shipmentId) throws Exception {
		com.drift.backend.company.TestAuthorisations.allowShipment(jdbc, shipmentId, importerCompanyId);
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"))
				.andExpect(status().isOk());
	}

	private void observe(String vessel, Instant ingested, String mmsi) {
		jdbc.update("""
				INSERT INTO vessel_observations (
					mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'SEEDED')
				""", mmsi, vessel, LATITUDE, LONGITUDE, SPEED, ingested.toString());
	}

	private void saveDeadline(String deadline) throws Exception {
		saveDeadline(lastShipmentId(), deadline);
	}

	private void saveDeadline(long shipmentId, String deadline) throws Exception {
		mvc.perform(patch("/api/shipments/" + shipmentId + "/transshipment-arrival-deadline")
				.header("Authorization", "Bearer " + importerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"transshipmentArrivalDeadline\":\"" + deadline + "\",\"expectedTransshipmentPort\":\"Singapore\"}"))
				.andExpect(status().isOk());
	}

	private void replacePort(long shipmentId, String port) throws Exception {
		long version = jdbc.queryForObject("SELECT version FROM shipments WHERE id = ?", Long.class, shipmentId);
		String reference = jdbc.queryForObject("SELECT shipment_reference FROM shipments WHERE id = ?", String.class, shipmentId);
		String vessel = jdbc.queryForObject("SELECT mother_vessel FROM shipments WHERE id = ?", String.class, shipmentId);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "%s",
						  "motherVessel": "%s",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00",
						  "version": %d
						}
						""".formatted(reference, port, vessel, version)))
				.andExpect(status().isOk());
	}

	private long lastShipmentId() {
		return jdbc.queryForObject("SELECT MAX(id) FROM shipments", Long.class);
	}

	private int count(String table, long shipmentId) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE shipment_id = ?", Integer.class, shipmentId);
		return count == null ? 0 : count;
	}

	private long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private String account(String role, long companyId) {
		String email = "cdg53-" + UUID.randomUUID() + "@example.test";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Impact User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}
}
