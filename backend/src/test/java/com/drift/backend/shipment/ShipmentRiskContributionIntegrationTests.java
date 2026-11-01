package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.shipment.PortCatalog.Coordinates;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CDG-212. Authorised detail returns the contributions from the same read as the connection risk. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(ShipmentRiskContributionClock.class)
class ShipmentRiskContributionIntegrationTests {

	static final Instant NOW = ShipmentRiskContributionClock.NOW;
	private static final String NO_POSITION = "No AIS position has been received for the mother vessel.";
	private static final String INVALID_COORDINATES = "The vessel or port coordinates cannot be used.";
	private static final String MISSING_PORT = "The transshipment port is missing.";
	private static final String UNKNOWN_PORT = "The transshipment port is not on the route map, so its coordinates cannot be used.";
	private static final String UNUSABLE_SPEED = "The mother vessel's speed is missing or below 0.5 knots.";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired ObjectMapper json;
	@Autowired EntityManager entityManager;
	@Autowired LatestAisPositions latestPositions;

	private final Coordinates singapore = PortCatalog.load().locate("Singapore").orElseThrow();
	private String forwarderToken;
	private String importerToken;
	private String outsiderToken;
	private Long importerCompanyId;

	@BeforeEach
	void accounts() throws Exception {
		latestPositions.clear();
		Long forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarderToken = login(account("FREIGHT_FORWARDER", forwarderCompanyId));
		importerToken = login(account("IMPORTER", importerCompanyId));
		outsiderToken = login(account("FREIGHT_FORWARDER", createCompany()));
	}

	@AfterEach
	void clearPositions() {
		latestPositions.clear();
	}

	@Test
	void remainingWindowBandsIncludeTheExactBoundariesAndAMissOfZeroSeconds() throws Exception {
		assertRemaining(Duration.ofHours(12).minusSeconds(1), "HIGH",
				"Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"11 hours 59 minutes 59 seconds");
		assertRemaining(Duration.ofHours(12), "MEDIUM",
				"Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"12 hours");
		assertRemaining(Duration.ofHours(24), "LOW",
				"At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"1 day");
		assertMissOfNothing();
	}

	@Test
	void anEstimateExactlySixHoursOldOrAtExactlyHalfAKnotStaysOnTheRemainingWindow() throws Exception {
		Instant received = NOW.minus(Duration.ofHours(6));
		JsonNode exactAge = detail(received, received, received.plus(Duration.ofHours(12)), new BigDecimal("12"));
		assertThat(exactAge.get("arrivalComparison").get("possiblyOutdated").asBoolean()).isFalse();
		assertThat(exactAge.get("remainingConnectionWindow").get("status").asText()).isEqualTo("AVAILABLE");
		assertAssessed(exactAge, "REMAINING_DURATION", "12 hours");
		assertThat(values(exactAge)).doesNotContain(ShipmentRiskContributions.OUTDATED);

		JsonNode exactSpeed = detail(NOW, NOW, NOW.plus(Duration.ofHours(12)), new BigDecimal("0.5"));
		assertThat(exactSpeed.get("arrivalComparison").get("unavailableReason").isNull()).isTrue();
		assertAssessed(exactSpeed, "REMAINING_DURATION", "12 hours");
		assertThat(values(exactSpeed)).doesNotContain(UNUSABLE_SPEED);
	}

	@Test
	void plannedFallbacksKeepEveryUnusedEstimateReasonDistinct() throws Exception {
		assertFallback(false, null, null, "Singapore", Duration.ofHours(6), "HIGH",
				"Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.",
				"6 hours", NO_POSITION);
		assertFallback(true, new BigDecimal("91"), new BigDecimal("12"), "Singapore", Duration.ofHours(12), "MEDIUM",
				"Less than 24 hours to transfer cargo between vessels. The planned connection window is being used.",
				"12 hours", INVALID_COORDINATES);
		assertFallback(true, singaporeLatitude(), new BigDecimal("0.499"), "Singapore", Duration.ofHours(18), "MEDIUM",
				"Less than 24 hours to transfer cargo between vessels. The planned connection window is being used.",
				"18 hours", UNUSABLE_SPEED);
		assertFallback(true, singaporeLatitude(), null, "Singapore", Duration.ofHours(2), "HIGH",
				"Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.",
				"2 hours", UNUSABLE_SPEED);
		assertFallback(true, singaporeLatitude(), new BigDecimal("12"), "", Duration.ofHours(24), "LOW",
				"At least 24 hours to transfer cargo between vessels. The planned connection window is being used.",
				"1 day", MISSING_PORT);
		assertFallback(true, singaporeLatitude(), new BigDecimal("12"), "Atlantis", Duration.ofHours(6), "HIGH",
				"Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.",
				"6 hours", UNKNOWN_PORT);

		Instant stale = NOW.minus(Duration.ofHours(6)).minusNanos(1);
		JsonNode outdated = detail(stale, stale, stale.plus(Duration.ofHours(6)), new BigDecimal("12"));
		assertThat(outdated.get("arrivalComparison").get("possiblyOutdated").asBoolean()).isTrue();
		assertThat(outdated.get("remainingConnectionWindow").isNull()).isTrue();
		assertThat(outdated.get("risk").get("level").asText()).isEqualTo("HIGH");
		assertThat(outdated.get("risk").get("explanation").asText()).isEqualTo(
				"Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.");
		assertContributions(outdated, "WINDOW_IN_USE", "Planned connection window", "PLANNED_DURATION", "6 hours",
				"ESTIMATE_NOT_USED", ShipmentRiskContributions.OUTDATED);
		assertThat(values(outdated)).doesNotContain(outdated.get("arrivalComparison").get("limitation").asText(),
				outdated.get("arrivalComparison").get("difference").asText());
	}

	@Test
	void anInvalidPlannedWindowAndAMissingPlannedTimeStayDistinct() throws Exception {
		long invalidId = create(NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)), "MV " + vesselName());
		allowIncompleteSchedule();
		jdbc.update("""
				UPDATE shipments
				SET planned_feeder_departure_at = planned_mother_arrival_at
				WHERE id = ?
				""", invalidId);
		entityManager.clear();
		JsonNode invalid = read(forwarderToken, invalidId);
		assertThat(invalid.get("connectionWindow").isNull()).isTrue();
		assertThat(invalid.get("risk").get("level").asText()).isEqualTo("CRITICAL");
		assertThat(invalid.get("risk").get("explanation").asText()).isEqualTo(
				"The feeder vessel is planned to depart at or before the mother vessel's arrival, leaving no time to transfer cargo. The planned connection window is being used.");
		assertContributions(invalid, "WINDOW_IN_USE", "Planned connection window", "PLANNED_WINDOW",
				ShipmentRiskContributions.NOT_POSITIVE, "ESTIMATE_NOT_USED", NO_POSITION);

		assertMissing(true, false, ShipmentRiskContributions.MISSING_MOTHER);
		assertMissing(false, true, ShipmentRiskContributions.MISSING_FEEDER);
		assertMissing(true, true, ShipmentRiskContributions.MISSING_BOTH);
	}

	@Test
	void aLinkedImporterSeesTheSameContributionsAndAnotherCompanySeesNone() throws Exception {
		JsonNode owner = detail(NOW, NOW, NOW.plus(Duration.ofHours(12)), new BigDecimal("12"));
		long id = owner.get("id").asLong();
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerCompanyId, id);
		entityManager.clear();

		int deliveries = count("shipment_risk_alert_deliveries");
		int transitions = count("shipment_risk_transitions");
		JsonNode importer = read(importerToken, id);
		assertThat(importer.get("riskContributions")).isEqualTo(owner.get("riskContributions"));
		assertThat(importer.get("risk")).isEqualTo(owner.get("risk"));
		assertThat(importer.get("remainingConnectionWindow")).isEqualTo(owner.get("remainingConnectionWindow"));
		assertThat(count("shipment_risk_alert_deliveries")).isEqualTo(deliveries);
		assertThat(count("shipment_risk_transitions")).isEqualTo(transitions);

		mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.riskContributions").doesNotExist())
				.andExpect(jsonPath("$.risk").doesNotExist())
				.andExpect(jsonPath("$.remainingConnectionWindow").doesNotExist());

		JsonNode listed = json.readTree(mvc.perform(get("/api/shipments")
				.param("reference", owner.get("shipmentReference").asText())
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertThat(listed).hasSize(1);
		assertThat(listed.get(0).has("riskContributions")).isFalse();
		assertThat(listed.get(0).has("risk")).isFalse();
	}

	private void assertRemaining(Duration remaining, String level, String explanation, String duration) throws Exception {
		JsonNode body = detail(NOW, NOW, NOW.plus(remaining), new BigDecimal("12"));
		assertThat(body.get("remainingConnectionWindow").get("status").asText()).isEqualTo("AVAILABLE");
		assertThat(body.get("remainingConnectionWindow").get("duration").asText()).isEqualTo(duration);
		assertThat(body.get("risk").get("level").asText()).isEqualTo(level);
		assertThat(body.get("risk").get("explanation").asText()).isEqualTo(explanation);
		assertContributions(body, "WINDOW_IN_USE", "Remaining connection window", "REMAINING_DURATION", duration);
		assertThat(values(body)).doesNotContain(explanation);
	}

	private void assertMissOfNothing() throws Exception {
		JsonNode body = detail(NOW, NOW.minus(Duration.ofHours(1)), NOW, new BigDecimal("12"));
		assertThat(body.get("remainingConnectionWindow").get("status").asText()).isEqualTo("MISSED");
		assertThat(body.get("remainingConnectionWindow").get("missedBy").asText()).isEqualTo("0 seconds");
		assertThat(body.get("risk").get("level").asText()).isEqualTo("CRITICAL");
		assertThat(body.get("risk").get("explanation").asText()).isEqualTo(
				"The connection is expected to be missed. The mother vessel's estimated arrival is at the planned feeder departure.");
		assertContributions(body, "WINDOW_IN_USE", "Remaining connection window", "MISSED_BY", "0 seconds");
	}

	private void assertFallback(boolean positioned, BigDecimal latitude, BigDecimal speed, String port, Duration planned,
			String level, String explanation, String duration, String reason) throws Exception {
		String name = vesselName();
		if (positioned) {
			if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
				allowUnusableLatitude();
			}
			observe(name, latitude, longitude(), speed, NOW);
		}
		long id = create(NOW, NOW.plus(planned), "MV " + name);
		if (!"Singapore".equals(port)) {
			jdbc.update("UPDATE shipments SET transshipment_port = ? WHERE id = ?", port, id);
			entityManager.clear();
		}
		JsonNode body = read(forwarderToken, id);
		assertThat(body.get("remainingConnectionWindow").isNull()).isTrue();
		assertThat(body.get("risk").get("level").asText()).isEqualTo(level);
		assertThat(body.get("risk").get("explanation").asText()).isEqualTo(explanation);
		assertContributions(body, "WINDOW_IN_USE", "Planned connection window", "PLANNED_DURATION", duration,
				"ESTIMATE_NOT_USED", reason);
		if (body.get("arrivalComparison").get("unavailableReason") != null
				&& !body.get("arrivalComparison").get("unavailableReason").isNull()) {
			assertThat(body.get("arrivalComparison").get("unavailableReason").get("message").asText()).isEqualTo(reason);
		}
	}

	private void assertMissing(boolean mother, boolean feeder, String sentence) throws Exception {
		long id = create(NOW, NOW.plus(Duration.ofHours(6)), "MV " + vesselName());
		allowIncompleteSchedule();
		jdbc.update("""
				UPDATE shipments
				SET planned_mother_arrival_at = CASE WHEN ? THEN NULL ELSE planned_mother_arrival_at END,
					planned_feeder_departure_at = CASE WHEN ? THEN NULL ELSE planned_feeder_departure_at END
				WHERE id = ?
				""", mother, feeder, id);
		entityManager.clear();
		JsonNode body = read(forwarderToken, id);
		assertThat(body.get("risk").isNull()).isTrue();
		assertThat(body.get("remainingConnectionWindow").isNull()).isTrue();
		assertThat(body.get("riskContributions").get("assessed").asBoolean()).isFalse();
		assertThat(body.get("riskContributions").get("notAssessed").asText()).isEqualTo(sentence);
		assertThat(body.get("riskContributions").get("contributions")).isEmpty();
		if (mother) {
			assertThat(body.get("arrivalComparison").isNull()).isTrue();
		} else {
			assertThat(body.get("arrivalComparison").get("unavailableReason").get("code").asText()).isEqualTo("NO_POSITION");
			assertThat(sentence).doesNotContain(NO_POSITION);
		}
	}

	private JsonNode detail(Instant received, Instant arrival, Instant departure, BigDecimal speed) throws Exception {
		String name = vesselName();
		observe(name, singaporeLatitude(), longitude(), speed, received);
		return read(forwarderToken, create(arrival, departure, "MV " + name));
	}

	private long create(Instant arrival, Instant departure, String motherVessel) throws Exception {
		String reference = "HBL-212-" + UUID.randomUUID().toString().substring(0, 8);
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Jakarta",
						  "destination": "Rotterdam",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "%s",
						  "plannedMotherArrivalAt": "%s",
						  "feederVessel": "MV Feeder",
						  "plannedFeederDepartureAt": "%s"
						}
						""".formatted(reference, motherVessel, arrival.atOffset(ZoneOffset.UTC),
						departure.atOffset(ZoneOffset.UTC))))
				.andExpect(status().isCreated())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("riskContributions");
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private JsonNode read(String token, long id) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private void observe(String name, BigDecimal latitude, BigDecimal longitude, BigDecimal speed, Instant ingestedAt) {
		latestPositions.record(new AisPosition(mmsi(), name, latitude, longitude, speed, null, null, 0, true, null,
				ingestedAt, AisPositionSource.SEEDED));
	}

	private void allowUnusableLatitude() {
		jdbc.queryForList("""
				SELECT con.conname
				FROM pg_constraint con
				JOIN pg_class rel ON rel.oid = con.conrelid
				WHERE rel.relname = 'vessel_observations' AND con.contype = 'c'
					AND pg_get_constraintdef(con.oid) LIKE '%latitude%'
				""", String.class).forEach(name -> jdbc.execute("ALTER TABLE vessel_observations DROP CONSTRAINT " + name));
	}

	private void allowIncompleteSchedule() {
		jdbc.queryForList("""
				SELECT con.conname
				FROM pg_constraint con
				JOIN pg_class rel ON rel.oid = con.conrelid
				WHERE rel.relname = 'shipments' AND con.contype = 'c'
					AND pg_get_constraintdef(con.oid) LIKE '%planned_feeder_departure_at%'
				""", String.class).forEach(name -> jdbc.execute("ALTER TABLE shipments DROP CONSTRAINT " + name));
		jdbc.execute("ALTER TABLE shipments ALTER COLUMN planned_mother_arrival_at DROP NOT NULL");
		jdbc.execute("ALTER TABLE shipments ALTER COLUMN planned_feeder_departure_at DROP NOT NULL");
	}

	private static void assertAssessed(JsonNode body, String measuredCode, String measuredValue) {
		assertContributions(body, "WINDOW_IN_USE", "Remaining connection window", measuredCode, measuredValue);
	}

	private static void assertContributions(JsonNode body, String... codeThenValue) {
		JsonNode contributions = body.get("riskContributions");
		assertThat(contributions.get("assessed").asBoolean()).isTrue();
		assertThat(contributions.get("notAssessed").isNull()).isTrue();
		assertThat(contributions.get("contributions")).hasSize(codeThenValue.length / 2);
		for (int index = 0; index < codeThenValue.length; index += 2) {
			JsonNode contribution = contributions.get("contributions").get(index / 2);
			assertThat(contribution.get("code").asText()).isEqualTo(codeThenValue[index]);
			assertThat(contribution.get("value").asText()).isEqualTo(codeThenValue[index + 1]);
			assertThat(contribution.get("label").asText()).isNotBlank();
		}
	}

	private static java.util.List<String> values(JsonNode body) {
		java.util.List<String> values = new java.util.ArrayList<>();
		body.get("riskContributions").get("contributions").forEach(contribution -> values.add(contribution.get("value").asText()));
		return values;
	}

	private BigDecimal singaporeLatitude() {
		return BigDecimal.valueOf(singapore.latitude());
	}

	private BigDecimal longitude() {
		return BigDecimal.valueOf(singapore.longitude());
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private String account(String role, Long companyId) {
		String email = "cdg212-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 212 User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private String login(String email) throws Exception {
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany() {
		String code = "CDG212" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-212 Other Company");
		return companyId(code);
	}

	private static String vesselName() {
		return "CDG212" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String mmsi() {
		return "5" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
	}

}
