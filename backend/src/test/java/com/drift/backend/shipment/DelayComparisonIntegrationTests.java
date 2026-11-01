package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG206_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG206_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG206_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG206_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG206_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG206_DB_PASSWORD}",
		"spring.flyway.out-of-order=false",
		"app.ais.enabled=false",
		"app.risk-alerts.evaluation.enabled=false",
		"app.risk-alerts.mail.enabled=false",
		"app.risk-alerts.mail.dispatch-enabled=false",
		"app.risk-alerts.activation.command=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG206_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG206_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = DelayComparisonDatabaseGuard.class)
@Import(DelayComparisonIntegrationTests.FixedClock.class)
class DelayComparisonIntegrationTests {

	private static final BigDecimal LATITUDE = new BigDecimal("1.26");
	private static final BigDecimal LONGITUDE = new BigDecimal("103.82");
	private static final BigDecimal SPEED = new BigDecimal("12.5");
	private static final OffsetDateTime PLANNED = OffsetDateTime.parse("2026-10-15T08:00:00+08:00");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final ArrivalEstimateCalculator calculator = new ArrivalEstimateCalculator(PortCatalog.load());

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired MovableClock clock;

	@Test
	void comparesBeforeEqualAndAfterAtMicrosecondPrecision() throws Exception {
		Instant ingested = Instant.parse("2026-10-28T16:00:00.500000Z");
		clock.set(ingested);
		Fixture fixture = linkedShipment("bounds");
		observe(fixture, ingested);
		OffsetDateTime estimate = calculatedEstimate(ingested);

		saveDeadline(fixture.importerToken(), fixture.shipmentId(), estimate.plus(1, ChronoUnit.MICROS));
		JsonNode before = detail(fixture.importerToken(), fixture.shipmentId());
		assertThat(instant(before, "/arrivalComparison/estimatedArrivalAt")).isEqualTo(estimate.toInstant());
		assertThat(instant(before, "/delayComparison/estimatedArrivalAt")).isEqualTo(estimate.toInstant());
		assertThat(before.at("/delayComparison/outcome").asString()).isEqualTo("NO_PREDICTED_IMPACT");
		assertThat(before.at("/delayComparison/reason").isNull()).isTrue();
		assertThat(before.at("/arrivalComparison/classification").asString()).isNotBlank();

		saveDeadline(fixture.importerToken(), fixture.shipmentId(),
				estimate.toInstant().atOffset(ZoneOffset.ofHours(8)));
		JsonNode equal = detail(fixture.forwarderToken(), fixture.shipmentId());
		assertThat(instant(equal, "/delayComparison/estimatedArrivalAt")).isEqualTo(estimate.toInstant());
		assertThat(instant(equal, "/delayComparison/deadline")).isEqualTo(estimate.toInstant());
		assertThat(instant(equal, "/transshipmentArrivalDeadline/deadline")).isEqualTo(estimate.toInstant());
		assertThat(equal.at("/delayComparison/outcome").asString()).isEqualTo("NO_PREDICTED_IMPACT");
		assertThat(equal.at("/delayComparison/recordedTransshipmentPort").asString()).isEqualTo("Singapore");

		OffsetDateTime afterDeadline = estimate.minus(1, ChronoUnit.MICROS);
		saveDeadline(fixture.importerToken(), fixture.shipmentId(), afterDeadline);
		JsonNode after = detail(fixture.importerToken(), fixture.shipmentId());
		assertThat(instant(after, "/arrivalComparison/estimatedArrivalAt")).isEqualTo(estimate.toInstant());
		assertThat(instant(after, "/delayComparison/deadline")).isEqualTo(afterDeadline.toInstant());
		assertThat(after.at("/delayComparison/outcome").asString()).isEqualTo("EXCEEDS_DEADLINE");
		assertThat(after.at("/delayComparison/reason").isNull()).isTrue();
		assertThat(after.at("/delayComparison/message").asString()).isEqualTo(DelayComparison.EXCEEDS);
	}

	@Test
	void keepsTheCalculatedEstimateFreshAtSixHoursAndStaleJustAfter() throws Exception {
		Instant ingested = Instant.parse("2026-10-28T04:00:00Z");
		clock.set(ingested.plus(Duration.ofHours(6)));
		Fixture fixture = linkedShipment("freshness");
		observe(fixture, ingested);
		OffsetDateTime estimate = calculatedEstimate(ingested);
		saveDeadline(fixture.importerToken(), fixture.shipmentId(), estimate);

		JsonNode fresh = detail(fixture.importerToken(), fixture.shipmentId());
		assertThat(fresh.at("/arrivalComparison/possiblyOutdated").asBoolean()).isFalse();
		assertThat(instant(fresh, "/arrivalComparison/estimatedArrivalAt")).isEqualTo(estimate.toInstant());
		assertThat(instant(fresh, "/arrivalComparison/lastReceivedAt")).isEqualTo(ingested);
		assertThat(fresh.at("/delayComparison/outcome").asString()).isEqualTo("NO_PREDICTED_IMPACT");
		assertThat(fresh.at("/delayComparison/reason").isNull()).isTrue();

		clock.set(ingested.plus(Duration.ofHours(6)).plus(1, ChronoUnit.MICROS));
		JsonNode stale = detail(fixture.importerToken(), fixture.shipmentId());
		assertThat(stale.at("/arrivalComparison/possiblyOutdated").asBoolean()).isTrue();
		assertThat(instant(stale, "/arrivalComparison/estimatedArrivalAt")).isEqualTo(estimate.toInstant());
		assertThat(stale.at("/delayComparison/outcome").isNull()).isTrue();
		assertThat(stale.at("/delayComparison/reason").asString()).isEqualTo("STALE_PREDICTION");
		assertThat(stale.at("/delayComparison/message").asString()).isEqualTo(DelayComparison.STALE);
		assertThat(instant(stale, "/delayComparison/deadline")).isEqualTo(estimate.toInstant());
	}

	@Test
	void showsTheSameComparisonToTheManagingCompanyAndLinkedImporter() throws Exception {
		Instant ingested = Instant.parse("2026-10-28T12:00:00Z");
		clock.set(ingested);
		Fixture fixture = linkedShipment("shared");
		observe(fixture, ingested);
		saveDeadline(fixture.importerToken(), fixture.shipmentId(), calculatedEstimate(ingested).plusSeconds(60));

		String managing = detail(fixture.forwarderToken(), fixture.shipmentId()).at("/delayComparison").toString();
		String importer = detail(fixture.importerToken(), fixture.shipmentId()).at("/delayComparison").toString();
		assertThat(importer).isEqualTo(managing);
		assertThat(managing).contains("NO_PREDICTED_IMPACT");
	}

	@Test
	void preservesForbiddenAndNotFoundResponses() throws Exception {
		clock.set(Instant.parse("2026-10-28T12:00:00Z"));
		Fixture fixture = linkedShipment("hidden");
		String other = tokenFor(account("IMPORTER", insertCompany("CDG206O" + UUID.randomUUID().toString().substring(0, 8))));

		mvc.perform(get("/api/shipments/" + fixture.shipmentId()).header("Authorization", "Bearer " + other))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.delayComparison").doesNotExist())
				.andExpect(jsonPath("$.transshipmentArrivalDeadline").doesNotExist());
		mvc.perform(get("/api/shipments/999999999").header("Authorization", "Bearer " + fixture.importerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE))
				.andExpect(jsonPath("$.delayComparison").doesNotExist());
	}

	@Test
	void followsTheCurrentImporterAndDropsAFormerDeadline() throws Exception {
		Instant ingested = Instant.parse("2026-10-28T12:00:00Z");
		clock.set(ingested);
		Fixture fixture = linkedShipment("relink");
		observe(fixture, ingested);
		OffsetDateTime estimate = calculatedEstimate(ingested);
		saveDeadline(fixture.importerToken(), fixture.shipmentId(), estimate.plusHours(2));
		Long otherCompany = insertCompany("CDG206B" + UUID.randomUUID().toString().substring(0, 8));
		String otherToken = tokenFor(account("IMPORTER", otherCompany));

		link(fixture.forwarderToken(), fixture.shipmentId(), null);
		JsonNode unlinked = detail(fixture.forwarderToken(), fixture.shipmentId());
		assertThat(unlinked.at("/transshipmentArrivalDeadline").isNull()).isTrue();
		assertThat(unlinked.at("/delayComparison/reason").asString()).isEqualTo("MISSING_CONSTRAINT");
		assertThat(unlinked.at("/delayComparison/deadline").isNull()).isTrue();
		assertThat(unlinked.at("/delayComparison/recordedTransshipmentPort").isNull()).isTrue();

		link(fixture.forwarderToken(), fixture.shipmentId(), otherCompany);
		JsonNode replaced = detail(otherToken, fixture.shipmentId());
		assertThat(replaced.at("/transshipmentArrivalDeadline").isNull()).isTrue();
		assertThat(replaced.at("/delayComparison/reason").asString()).isEqualTo("MISSING_CONSTRAINT");

		saveDeadline(otherToken, fixture.shipmentId(), estimate.minusSeconds(1));
		JsonNode otherView = detail(fixture.forwarderToken(), fixture.shipmentId());
		assertThat(otherView.at("/delayComparison/outcome").asString()).isEqualTo("EXCEEDS_DEADLINE");
		assertThat(instant(otherView, "/delayComparison/deadline")).isEqualTo(estimate.minusSeconds(1).toInstant());

		link(fixture.forwarderToken(), fixture.shipmentId(), fixture.importerCompanyId());
		JsonNode restored = detail(fixture.importerToken(), fixture.shipmentId());
		assertThat(restored.at("/delayComparison/outcome").asString()).isEqualTo("NO_PREDICTED_IMPACT");
		assertThat(instant(restored, "/transshipmentArrivalDeadline/deadline"))
				.isEqualTo(estimate.plusHours(2).toInstant());
		assertThat(deadlineRow(fixture.shipmentId(), otherCompany)).isEqualTo(estimate.minusSeconds(1).toInstant());
	}

	@Test
	void anInapplicableRecordedPortBlocksTheComparison() throws Exception {
		Instant ingested = Instant.parse("2026-10-28T12:00:00Z");
		clock.set(ingested);
		Fixture fixture = linkedShipment("port");
		observe(fixture, ingested);
		OffsetDateTime estimate = calculatedEstimate(ingested);
		saveDeadline(fixture.importerToken(), fixture.shipmentId(), estimate.plusHours(1));
		replacePort(fixture.forwarderToken(), fixture.shipmentId(), "Tanjung Pelepas");

		JsonNode detail = detail(fixture.forwarderToken(), fixture.shipmentId());
		assertThat(detail.at("/transshipmentArrivalDeadline/applicable").asBoolean()).isFalse();
		assertThat(detail.at("/transshipmentArrivalDeadline/transshipmentPort").asString()).isEqualTo("Singapore");
		assertThat(detail.at("/delayComparison/reason").asString()).isEqualTo("INCOMPATIBLE_MILESTONE");
		assertThat(detail.at("/delayComparison/outcome").isNull()).isTrue();
		assertThat(detail.at("/delayComparison/recordedTransshipmentPort").asString()).isEqualTo("Singapore");
		assertThat(instant(detail, "/delayComparison/estimatedArrivalAt"))
				.isEqualTo(instant(detail, "/arrivalComparison/estimatedArrivalAt"));
		assertThat(deadlineRow(fixture.shipmentId(), fixture.importerCompanyId())).isEqualTo(estimate.plusHours(1).toInstant());
	}

	@Test
	void anArchivedShipmentStaysReadableWithoutChangingTheStoredDeadline() throws Exception {
		Instant ingested = Instant.parse("2026-10-28T12:00:00Z");
		clock.set(ingested);
		Fixture fixture = linkedShipment("archived");
		observe(fixture, ingested);
		OffsetDateTime estimate = calculatedEstimate(ingested);
		saveDeadline(fixture.importerToken(), fixture.shipmentId(), estimate);
		String admin = tokenFor(account("ADMIN", fixture.forwarderCompanyId()));
		mvc.perform(patch("/api/shipments/" + fixture.shipmentId() + "/status")
				.header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ARCHIVED\"}"))
				.andExpect(status().isOk());

		JsonNode detail = detail(fixture.importerToken(), fixture.shipmentId());
		assertThat(detail.at("/status").asString()).isEqualTo("ARCHIVED");
		assertThat(detail.at("/delayComparison/outcome").asString()).isEqualTo("NO_PREDICTED_IMPACT");
		mvc.perform(patch("/api/shipments/" + fixture.shipmentId() + "/transshipment-arrival-deadline")
				.header("Authorization", "Bearer " + fixture.importerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(deadlineBody(null, "Singapore")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentNotEditableException.MESSAGE));
		assertThat(deadlineRow(fixture.shipmentId(), fixture.importerCompanyId())).isEqualTo(estimate.toInstant());
	}

	@Test
	void leavesTheEstimateRiskAndStoredShipmentUnchanged() throws Exception {
		Instant ingested = Instant.parse("2026-10-28T12:00:00Z");
		clock.set(ingested);
		Fixture fixture = linkedShipment("stable");
		observe(fixture, ingested);
		saveDeadline(fixture.importerToken(), fixture.shipmentId(), calculatedEstimate(ingested));
		long version = version(fixture.shipmentId());
		JsonNode first = detail(fixture.forwarderToken(), fixture.shipmentId());
		JsonNode second = detail(fixture.importerToken(), fixture.shipmentId());

		assertThat(second.at("/arrivalComparison").toString()).isEqualTo(first.at("/arrivalComparison").toString());
		assertThat(second.at("/remainingConnectionWindow").toString())
				.isEqualTo(first.at("/remainingConnectionWindow").toString());
		assertThat(second.at("/risk").toString()).isEqualTo(first.at("/risk").toString());
		assertThat(second.at("/delayComparison/estimatedArrivalAt").asString())
				.isEqualTo(first.at("/arrivalComparison/estimatedArrivalAt").asString());
		assertThat(second.at("/delayComparison/deadline").asString())
				.isEqualTo(first.at("/transshipmentArrivalDeadline/deadline").asString());
		assertThat(version(fixture.shipmentId())).isEqualTo(version);
		assertThat(deadlineRow(fixture.shipmentId(), fixture.importerCompanyId()))
				.isEqualTo(calculatedEstimate(ingested).toInstant());

		String list = mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + fixture.forwarderToken()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(list).doesNotContain("delayComparison");
	}

	private OffsetDateTime calculatedEstimate(Instant ingested) {
		ArrivalEstimate estimate = calculator.estimate(new ArrivalFix(LATITUDE, LONGITUDE, SPEED, ingested), "Singapore",
				PLANNED, Clock.fixed(clock.instant(), ZoneOffset.UTC));
		assertThat(estimate.unavailableReason()).isNull();
		return estimate.estimatedArrivalAt();
	}

	private JsonNode detail(String token, Long shipmentId) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private static Instant instant(JsonNode body, String pointer) {
		JsonNode value = body.at(pointer);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		return OffsetDateTime.parse(value.asString()).toInstant();
	}

	private void observe(Fixture fixture, Instant ingested) {
		jdbc.update("""
				INSERT INTO vessel_observations (
					mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'SEEDED')
				""", fixture.mmsi(), fixture.vessel(), LATITUDE, LONGITUDE, SPEED, ingested.toString());
	}

	private Fixture linkedShipment(String label) throws Exception {
		Long forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		Long importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		String forwarderToken = tokenFor(account("FREIGHT_FORWARDER", forwarderCompanyId));
		String importerToken = tokenFor(account("IMPORTER", importerCompanyId));
		String vessel = "MV CDG206 " + label + " " + UUID.randomUUID().toString().substring(0, 8);
		String mmsi = "5" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
		Long shipmentId = createShipment(forwarderToken, "CDG206-" + label + "-" + UUID.randomUUID(), vessel);
		link(forwarderToken, shipmentId, importerCompanyId);
		return new Fixture(shipmentId, forwarderCompanyId, importerCompanyId, forwarderToken, importerToken, vessel, mmsi);
	}

	private Long createShipment(String token, String reference, String vessel) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
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
						""".formatted(reference, vessel)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private void saveDeadline(String token, Long shipmentId, OffsetDateTime deadline) throws Exception {
		mvc.perform(patch("/api/shipments/" + shipmentId + "/transshipment-arrival-deadline")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(deadlineBody(deadline.toString(), "Singapore")))
				.andExpect(status().isOk());
	}

	private void replacePort(String token, Long shipmentId, String port) throws Exception {
		long version = version(shipmentId);
		String reference = jdbc.queryForObject("SELECT shipment_reference FROM shipments WHERE id = ?", String.class,
				shipmentId);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
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
						""".formatted(reference, port,
						jdbc.queryForObject("SELECT mother_vessel FROM shipments WHERE id = ?", String.class, shipmentId),
						version)))
				.andExpect(status().isOk());
	}

	private void link(String token, Long shipmentId, Long importerCompanyId) throws Exception {
		String body = importerCompanyId == null ? "{\"importerCompanyId\":null}"
				: "{\"importerCompanyId\":" + importerCompanyId + "}";
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk());
	}

	private static String deadlineBody(String deadline, String port) {
		String value = deadline == null ? "null" : "\"" + deadline + "\"";
		return "{\"transshipmentArrivalDeadline\":" + value + ",\"expectedTransshipmentPort\":\"" + port + "\"}";
	}

	private String account(String role, Long companyId) {
		String email = "cdg206-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Comparison User', ?, ?, id, ?
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

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long insertCompany(String code) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, code);
		return companyId(code);
	}

	private long version(Long shipmentId) {
		return jdbc.queryForObject("SELECT version FROM shipments WHERE id = ?", Long.class, shipmentId);
	}

	private Instant deadlineRow(Long shipmentId, Long importerCompanyId) {
		return jdbc.queryForObject("""
				SELECT transshipment_arrival_deadline
				FROM shipment_importer_constraints
				WHERE shipment_id = ? AND importer_company_id = ?
				""", Instant.class, shipmentId, importerCompanyId);
	}

	private record Fixture(Long shipmentId, Long forwarderCompanyId, Long importerCompanyId, String forwarderToken,
			String importerToken, String vessel, String mmsi) {
	}

	@TestConfiguration
	static class FixedClock {
		@Bean
		MovableClock clock() {
			return new MovableClock();
		}
	}

	static final class MovableClock extends Clock {
		private volatile Instant instant = Instant.parse("2026-10-28T00:00:00Z");

		void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) {
				throw new UnsupportedOperationException("The comparison clock stays on UTC.");
			}
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
