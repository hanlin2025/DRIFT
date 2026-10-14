package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG152_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG152_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG152_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG152_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG152_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG152_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG152_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG152_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ArrivalCoverageDatabaseGuard.class)
@Import(ArrivalComparisonCoverageIntegrationTests.FixedClock.class)
@org.springframework.transaction.annotation.Transactional
class ArrivalComparisonCoverageIntegrationTests {

	static final Instant RECEIVED = Instant.parse("2026-10-13T04:00:00Z");
	static final MovableClock CLOCK = new MovableClock();
	private static final String LIMITATION = "Approximate arrival — based on straight-line distance and current speed.";
	private static final String DENIED = """
			{"message":"%s"}""".formatted(ShipmentDetailForbiddenException.MESSAGE).trim();
	private static final BigDecimal LATITUDE = new BigDecimal("1.26");
	private static final BigDecimal LONGITUDE = new BigDecimal("103.82");
	private static final Duration MICROSECOND = Duration.ofNanos(1_000);

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired ObjectMapper json;
	@Autowired EntityManager entities;

	@BeforeEach
	void resetClock() {
		CLOCK.set(RECEIVED);
	}

	@AfterEach
	void restoreClock() {
		CLOCK.set(RECEIVED);
	}

	@Test
	void exactTwoHoursStaysFixedWhenTheClockPassesSixHours() throws Exception {
		at(RECEIVED.plus(Duration.ofHours(6)));
		String token = tokenFor(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		observe("On Time", new BigDecimal("0.5"), RECEIVED);
		Long shipmentId = create(token, "On Time", RECEIVED.minus(Duration.ofHours(2)));

		JsonNode first = read(token, shipmentId);
		assertAvailable(first, RECEIVED, new BigDecimal("0.5"), "ON_SCHEDULE",
				"On schedule · estimated 2h later than planned", Duration.ofHours(2).toSeconds(), 0, false);
		assertScheduleUnchanged(first);

		CLOCK.advance(MICROSECOND);
		JsonNode second = read(token, shipmentId);
		assertAvailable(second, RECEIVED, new BigDecimal("0.5"), "ON_SCHEDULE",
				"On schedule · estimated 2h later than planned", Duration.ofHours(2).toSeconds(), 0, true);
		assertThat(instant(second.get("arrivalComparison").get("estimatedArrivalAt")))
				.isEqualTo(instant(first.get("arrivalComparison").get("estimatedArrivalAt")));
	}

	@Test
	void oneMicrosecondPastTwoHoursIsBehindPlan() throws Exception {
		String token = tokenFor(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		observe("Just Over", new BigDecimal("0.5"), RECEIVED);
		Long shipmentId = create(token, "Just Over", RECEIVED.minus(Duration.ofHours(2)).minus(MICROSECOND));

		JsonNode body = read(token, shipmentId);
		assertAvailable(body, RECEIVED, new BigDecimal("0.5"), "BEHIND_PLAN",
				"Behind plan · estimated just over 2h later than planned", Duration.ofHours(2).toSeconds(), 1_000, false);
		assertThat(body.get("arrivalComparison").get("difference").asText())
				.isNotEqualTo("Behind plan · estimated 2h later than planned");
	}

	@Test
	void earlierIsAheadOfPlanAndAnOnScheduleGapRemainsVisible() throws Exception {
		String token = tokenFor(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		observe("Ahead", new BigDecimal("0.5"), RECEIVED);
		observe("Gap", new BigDecimal("0.5"), RECEIVED);
		Long aheadId = create(token, "Ahead", RECEIVED.plus(Duration.ofMinutes(90)));
		Long gapId = create(token, "Gap", RECEIVED.minus(Duration.ofMinutes(90)));

		assertAvailable(read(token, aheadId), RECEIVED, new BigDecimal("0.5"), "AHEAD_OF_PLAN",
				"Ahead of plan · estimated 1h 30m earlier than planned", -90 * 60L, 0, false);
		assertAvailable(read(token, gapId), RECEIVED, new BigDecimal("0.5"), "ON_SCHEDULE",
				"On schedule · estimated 1h 30m later than planned", 90 * 60L, 0, false);
	}

	@Test
	void sixHoursLaterIsBehindPlan() throws Exception {
		String token = tokenFor(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		observe("Six Hours", new BigDecimal("0.5"), RECEIVED);
		Long shipmentId = create(token, "Six Hours", RECEIVED.minus(Duration.ofHours(6)));

		assertAvailable(read(token, shipmentId), RECEIVED, new BigDecimal("0.5"), "BEHIND_PLAN",
				"Behind plan · estimated 6h later than planned", Duration.ofHours(6).toSeconds(), 0, false);
	}

	@Test
	void missingAndUnusableInputsReturnAReasonAndNoArrivalTime() throws Exception {
		String token = tokenFor(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		observe("Slow", new BigDecimal("0.499"), RECEIVED);
		observe("Still", null, RECEIVED);
		observe("Unknown", new BigDecimal("0.5"), RECEIVED);
		observe("Blank", new BigDecimal("0.5"), RECEIVED);
		Long missingId = create(token, "Absent", RECEIVED);
		Long slowId = create(token, "Slow", RECEIVED);
		Long stillId = create(token, "Still", RECEIVED);
		Long unknownId = create(token, "Unknown", RECEIVED, "SGSIN");
		Long blankId = create(token, "Blank", RECEIVED);
		entities.createNativeQuery("UPDATE shipments SET transshipment_port = '' WHERE id = :id")
				.setParameter("id", blankId)
				.executeUpdate();
		entities.flush();
		entities.clear();

		assertUnavailable(read(token, missingId), "NO_POSITION",
				"No AIS position has been received for the mother vessel.");
		assertUnavailable(read(token, slowId), "UNUSABLE_SPEED",
				"The mother vessel's speed is missing or below 0.5 knots.");
		assertUnavailable(read(token, stillId), "UNUSABLE_SPEED",
				"The mother vessel's speed is missing or below 0.5 knots.");
		assertUnavailable(read(token, unknownId), "UNKNOWN_PORT",
				"The transshipment port is not on the route map, so its coordinates cannot be used.");
		JsonNode blank = read(token, blankId);
		assertUnavailable(blank, "MISSING_PORT", "The transshipment port is missing.");
		assertScheduleUnchanged(blank);
		assertScheduleUnchanged(read(token, slowId));
	}

	@Test
	void ownerAndLinkedImporterReceiveTheComparisonAndAnotherCompanyDoesNot() throws Exception {
		Long ownerCompanyId = companyId("HARBOURLINE_DEMO");
		Long importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		String owner = tokenFor(account("FREIGHT_FORWARDER", ownerCompanyId));
		String importer = tokenFor(account("IMPORTER", importerCompanyId));
		observe("Shared", new BigDecimal("0.5"), RECEIVED);
		Long shipmentId = create(owner, "Shared", RECEIVED.minus(Duration.ofHours(2)));

		MvcResult linked = mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode linkBody = json.readTree(linked.getResponse().getContentAsString());
		JsonNode ownerBody = read(owner, shipmentId);
		JsonNode importerBody = read(importer, shipmentId);

		assertAvailable(linkBody, RECEIVED, new BigDecimal("0.5"), "ON_SCHEDULE",
				"On schedule · estimated 2h later than planned", Duration.ofHours(2).toSeconds(), 0, false);
		assertThat(instant(ownerBody.get("arrivalComparison").get("estimatedArrivalAt")))
				.isEqualTo(instant(linkBody.get("arrivalComparison").get("estimatedArrivalAt")));
		assertThat(instant(importerBody.get("arrivalComparison").get("estimatedArrivalAt")))
				.isEqualTo(instant(linkBody.get("arrivalComparison").get("estimatedArrivalAt")));
		assertThat(importerBody.get("companyId").asLong()).isEqualTo(ownerCompanyId);
		assertScheduleUnchanged(importerBody);

		String other = tokenFor(account("IMPORTER", createCompany()));
		mvc.perform(get("/api/shipments/" + shipmentId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
				.andExpect(status().isForbidden())
				.andExpect(content().json(DENIED, true));
	}

	private void at(Instant instant) {
		CLOCK.set(instant);
	}

	private void assertAvailable(JsonNode body, Instant estimated, BigDecimal speed, String classification, String difference,
			long seconds, int nanos, boolean outdated) {
		JsonNode comparison = body.get("arrivalComparison");
		assertThat(comparison.get("limitation").asText()).isEqualTo(LIMITATION);
		assertThat(comparison.get("classification").asText()).isEqualTo(classification);
		assertThat(comparison.get("difference").asText()).isEqualTo(difference);
		assertThat(comparison.get("differenceSeconds").asLong()).isEqualTo(seconds);
		assertThat(comparison.get("differenceNanos").asInt()).isEqualTo(nanos);
		assertThat(comparison.get("possiblyOutdated").asBoolean()).isEqualTo(outdated);
		assertThat(comparison.has("unavailableReason")).isTrue();
		assertThat(comparison.get("unavailableReason").isNull()).isTrue();
		assertThat(instant(comparison.get("estimatedArrivalAt"))).isEqualTo(estimated);
		assertThat(instant(comparison.get("lastReceivedAt"))).isEqualTo(estimated);
		assertThat(instant(body.get("motherVesselPosition").get("ingestedAt"))).isEqualTo(estimated);
		assertThat(new BigDecimal(comparison.get("speedOverGroundKnots").asText())).isEqualByComparingTo(speed);
		assertThat(comparison.has("plannedMotherArrivalAt")).isFalse();
	}

	private void assertUnavailable(JsonNode body, String code, String message) {
		JsonNode comparison = body.get("arrivalComparison");
		for (String field : new String[] {"estimatedArrivalAt", "limitation", "speedOverGroundKnots", "classification",
				"difference", "differenceSeconds", "differenceNanos", "lastReceivedAt", "possiblyOutdated"}) {
			assertThat(comparison.has(field)).isTrue();
			assertThat(comparison.get(field).isNull()).isTrue();
		}
		assertThat(comparison.get("unavailableReason").get("code").asText()).isEqualTo(code);
		assertThat(comparison.get("unavailableReason").get("message").asText()).isEqualTo(message);
	}

	private static void assertScheduleUnchanged(JsonNode body) {
		assertThat(body.get("connectionWindow").get("duration").asText()).isEqualTo("6 hours");
		assertThat(body.get("connectionWindow").get("totalSeconds").asLong()).isEqualTo(21_600L);
		assertThat(body.get("risk").get("level").asText()).isEqualTo("HIGH");
		assertThat(body.get("risk").get("explanation").asText())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels.");
	}

	private static Instant instant(JsonNode value) {
		return OffsetDateTime.parse(value.asText()).toInstant();
	}

	private JsonNode read(String token, Long shipmentId) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + shipmentId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private Long create(String token, String vessel, Instant planned) throws Exception {
		return create(token, vessel, planned, "Singapore");
	}

	private Long create(String token, String vessel, Instant planned, String port) throws Exception {
		OffsetDateTime arrival = planned.atOffset(ZoneOffset.UTC);
		String payload = """
				{
				  "shipmentReference":"%s",
				  "origin":"Jakarta",
				  "destination":"Rotterdam",
				  "transshipmentPort":"%s",
				  "motherVessel":"%s",
				  "plannedMotherArrivalAt":"%s",
				  "feederVessel":"Feeder",
				  "plannedFeederDepartureAt":"%s"
				}
				""".formatted("HBL-CDG152-" + UUID.randomUUID(), port, vessel, arrival, arrival.plusHours(6));
		MvcResult result = mvc.perform(post("/api/shipments")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private void observe(String vesselName, BigDecimal speed, Instant ingested) {
		jdbc.update("""
				INSERT INTO vessel_observations (
					mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'SEEDED')
				""", mmsi(), vesselName, LATITUDE, LONGITUDE, speed, ingested.toString());
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
		String email = "cdg152-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-152 User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany() {
		String code = "CDG152" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-152 Other Company");
		return companyId(code);
	}

	private static String mmsi() {
		return "5" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	@TestConfiguration
	static class FixedClock {
		@Bean
		Clock clock() {
			return CLOCK;
		}
	}

	static final class MovableClock extends Clock {
		private volatile Instant instant = RECEIVED;

		void set(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			this.instant = this.instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) {
				throw new UnsupportedOperationException("The coverage clock stays on UTC.");
			}
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
