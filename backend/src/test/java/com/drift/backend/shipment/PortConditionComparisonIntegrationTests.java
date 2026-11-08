package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CDG-277. An authorised shipment returns the CDG-276 comparison from the same read. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PortConditionComparisonIntegrationTests {

	private static final BigDecimal SINGAPORE_LATITUDE = new BigDecimal("1.264");
	private static final BigDecimal SINGAPORE_LONGITUDE = new BigDecimal("103.840");
	private static final BigDecimal PELEPAS_LATITUDE = new BigDecimal("1.362");
	private static final BigDecimal PELEPAS_LONGITUDE = new BigDecimal("103.549");
	private static final BigDecimal FAR_LATITUDE = new BigDecimal("1.698");
	private static final BigDecimal JAKARTA_LATITUDE = new BigDecimal("-6.1");
	private static final BigDecimal JAKARTA_LONGITUDE = new BigDecimal("106.88");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired ObjectMapper json;
	@Autowired EntityManager entityManager;
	@Autowired LatestAisPositions latestPositions;

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
	void anAuthorisedReadReturnsACompletedComparisonAndKeepsTheRestOfTheDetail() throws Exception {
		assertThat(storedComparisonColumns()).isZero();
		Instant now = Instant.now();
		JsonNode match = shipment("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8.0"), 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("4.0"), now.minus(Duration.ofHours(3)));
		assertCompleted(match, "MATCHES_NORMAL", PortConditionComparison.MATCHES, "Singapore");
		assertThat(match.get("remainingConnectionWindow").get("status").asText()).isEqualTo("AVAILABLE");
		assertThat(match.get("risk").get("level").asText()).isEqualTo("LOW");
		assertThat(match.get("portConditionComparison").get("message").asText())
				.isNotEqualTo(match.get("risk").get("explanation").asText());
		assertThat(match.get("delayComparison").get("outcome").isNull()).isTrue();
		assertThat(match.get("recommendation").get("available").asBoolean()).isFalse();
		assertThat(match.get("recommendation").get("impact").get("detail").asText())
				.isEqualTo(match.get("delayComparison").get("message").asText());
		assertThat(match.get("delayComparison").isNull()).isFalse();
		assertThat(match.get("aisFeed").isNull()).isFalse();
		assertThat(match.get("arrivalComparison").get("possiblyOutdated").asBoolean()).isFalse();

		JsonNode equal = shipment("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("4.0"), 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("4.0"), now.minus(Duration.ofHours(3)));
		assertCompleted(equal, "MATCHES_NORMAL", PortConditionComparison.MATCHES, "Singapore");

		JsonNode slower = shipment("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("3.5"), 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("6.0"), now.minus(Duration.ofHours(3)));
		assertCompleted(slower, "SLOWER_THAN_OWN_REPORTS", PortConditionComparison.SLOWER, "Singapore");
		assertThat(slower.get("portConditionComparison").get("message").asText()).doesNotContain("congested");

		JsonNode pelepas = shipment("Tanjung Pelepas", PELEPAS_LATITUDE, PELEPAS_LONGITUDE, new BigDecimal("9"), 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("5"), now.minus(Duration.ofHours(2)));
		assertCompleted(pelepas, "MATCHES_NORMAL", PortConditionComparison.MATCHES, "Tanjung Pelepas");

		JsonNode withinSixHours = shipment("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8"), 0,
				now.minus(Duration.ofHours(2)), new BigDecimal("8"), now.minus(Duration.ofHours(4)));
		assertCompleted(withinSixHours, "MATCHES_NORMAL", PortConditionComparison.MATCHES, "Singapore");
		assertThat(withinSixHours.get("arrivalComparison").get("possiblyOutdated").asBoolean()).isFalse();
		assertThat(withinSixHours.get("portConditionComparison").get("message").asText())
				.doesNotContain("Live", "Delayed", "Offline");

		JsonNode belowEstimateCutoff = shipment("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE,
				new BigDecimal("0.4"), 0, now.minus(Duration.ofMinutes(2)), new BigDecimal("0.4"),
				now.minus(Duration.ofHours(3)));
		assertCompleted(belowEstimateCutoff, "MATCHES_NORMAL", PortConditionComparison.MATCHES, "Singapore");
		assertThat(belowEstimateCutoff.get("arrivalComparison").get("unavailableReason").get("code").asText())
				.isEqualTo("UNUSABLE_SPEED");
		assertThat(belowEstimateCutoff.get("remainingConnectionWindow").isNull()).isTrue();

		JsonNode exactlyOneKnot = shipment("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("1.0"), 5,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("1.0"), now.minus(Duration.ofHours(3)));
		assertCompleted(exactlyOneKnot, "MATCHES_NORMAL", PortConditionComparison.MATCHES, "Singapore");
		assertThat(exactlyOneKnot.get("motherOperationalState").get("unreliable").asBoolean()).isFalse();
		assertThat(storedComparisonColumns()).isZero();
	}

	@Test
	void eachUnavailableReasonStaysDistinctFromAMatch() throws Exception {
		Instant now = Instant.now();
		String name = vesselName();
		String mmsi = mmsi();
		long noReport = create("Singapore", "MV " + name);
		JsonNode missingReport = read(forwarderToken, noReport);
		assertUnavailable(missingReport, "NO_REPORT", PortConditionComparison.NO_REPORT_MESSAGE);
		assertThat(missingReport.get("motherVesselPosition").isNull()).isTrue();
		assertThat(missingReport.get("motherOperationalState").get("label").asText()).isEqualTo("Unknown");
		assertThat(missingReport.get("feederOperationalState").get("ingestedAt").isNull()).isTrue();

		String feeder = vesselName();
		observe(mmsi(), feeder, SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("1"), 1, true,
				now.minus(Duration.ofMinutes(2)), AisPositionSource.AIS_STREAM);
		long feederOnly = create("Singapore", "MV " + vesselName(), "MV " + feeder);
		JsonNode feederComparison = read(forwarderToken, feederOnly);
		assertUnavailable(feederComparison, "NO_REPORT", PortConditionComparison.NO_REPORT_MESSAGE);
		assertThat(feederComparison.get("feederOperationalState").get("description").asText())
				.contains("near Singapore");
		assertThat(feederComparison.get("portConditionComparison").get("vessel").asText()).isEqualTo("MOTHER");

		JsonNode otherPort = placed("Jakarta", JAKARTA_LATITUDE, JAKARTA_LONGITUDE, new BigDecimal("12"), 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("12"), now.minus(Duration.ofHours(3)));
		assertUnavailable(otherPort, "PORT_NOT_DEFINED", PortConditionComparison.PORT_NOT_DEFINED_MESSAGE);
		assertThat(otherPort.get("portConditionComparison").get("knownPort").isNull()).isTrue();
		assertThat(otherPort.get("motherOperationalState").get("description").asText())
				.isEqualTo(otherPort.get("motherOperationalState").get("label").asText());
		assertThat(otherPort.get("arrivalComparison").get("unavailableReason").isNull()).isTrue();

		JsonNode far = placed("Singapore", FAR_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8"), 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("1"), now.minus(Duration.ofHours(3)));
		assertUnavailable(far, "NOT_NEAR_PORT", PortConditionComparison.NOT_NEAR_PORT_MESSAGE);
		assertThat(far.get("motherOperationalState").get("description").asText()).endsWith(", far from port");

		JsonNode unreliable = placed("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("1.1"), 5,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("0.2"), now.minus(Duration.ofHours(3)));
		assertUnavailable(unreliable, "UNRELIABLE_REPORT", PortConditionComparison.UNRELIABLE_REPORT_MESSAGE);
		assertThat(unreliable.get("motherOperationalState").get("unreliable").asBoolean()).isTrue();
		assertThat(unreliable.get("motherOperationalState").get("description").asText())
				.isEqualTo("Moored near Singapore");

		JsonNode tooOld = placed("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8"), 0,
				now.minus(Duration.ofHours(7)), new BigDecimal("8"), now.minus(Duration.ofHours(9)));
		assertUnavailable(tooOld, "POSITION_TOO_OLD", PortConditionComparison.POSITION_TOO_OLD_MESSAGE);
		assertThat(tooOld.get("arrivalComparison").get("possiblyOutdated").asBoolean()).isTrue();
		assertThat(tooOld.get("remainingConnectionWindow").isNull()).isTrue();

		JsonNode noSpeed = placed("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, null, 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("4"), now.minus(Duration.ofHours(3)));
		assertUnavailable(noSpeed, "MISSING_SPEED", PortConditionComparison.MISSING_SPEED_MESSAGE);

		JsonNode noBaseline = placed("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8"), 0,
				now.minus(Duration.ofMinutes(2)), null, null);
		assertUnavailable(noBaseline, "NO_BASELINE", PortConditionComparison.NO_BASELINE_MESSAGE);
		assertThat(noBaseline.get("portConditionComparison").get("message").asText())
				.isNotEqualTo(PortConditionComparison.MATCHES);

		String otherMmsi = mmsi();
		String isolated = vesselName();
		observe(otherMmsi, isolated, SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("1"), 0, true,
				now.minus(Duration.ofHours(3)), AisPositionSource.AIS_STREAM);
		observe(mmsi(), isolated, SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8"), 0, true,
				now.minus(Duration.ofMinutes(2)), AisPositionSource.AIS_STREAM);
		JsonNode differentVessel = read(forwarderToken, create("Singapore", "MV " + isolated));
		assertUnavailable(differentVessel, "NO_BASELINE", PortConditionComparison.NO_BASELINE_MESSAGE);

		String invalidName = vesselName();
		String invalidMmsi = mmsi();
		observe(invalidMmsi, invalidName, SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("1"), 0, false,
				now.minus(Duration.ofHours(3)), AisPositionSource.SEEDED);
		observe(invalidMmsi, invalidName, SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8"), 0, true,
				now.minus(Duration.ofMinutes(2)), AisPositionSource.AIS_STREAM);
		JsonNode invalidEarlier = read(forwarderToken, create("Singapore", "MV " + invalidName));
		assertUnavailable(invalidEarlier, "NO_BASELINE", PortConditionComparison.NO_BASELINE_MESSAGE);
		assertThat(invalidEarlier.get("portConditionComparison").get("source").asText()).isEqualTo("AIS_STREAM");
	}

	@Test
	void theManagingCompanyAndALinkedImporterSeeItAndAnotherCompanyDoesNot() throws Exception {
		Instant now = Instant.now();
		int deliveries = count("shipment_risk_alert_deliveries");
		int transitions = count("shipment_risk_transitions");
		JsonNode owner = shipment("Singapore", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, new BigDecimal("8"), 0,
				now.minus(Duration.ofMinutes(2)), new BigDecimal("5"), now.minus(Duration.ofHours(3)));
		long id = owner.get("id").asLong();
		int observations = count("vessel_observations");
		JsonNode reread = read(forwarderToken, id);
		assertThat(reread.get("portConditionComparison")).isEqualTo(owner.get("portConditionComparison"));
		assertThat(count("vessel_observations")).isEqualTo(observations);
		assertThat(count("shipment_risk_alert_deliveries")).isEqualTo(deliveries);
		assertThat(count("shipment_risk_transitions")).isEqualTo(transitions);

		JsonNode linked = json.readTree(mvc.perform(patch("/api/shipments/" + id + "/link-importer")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertThat(linked.get("portConditionComparison")).isEqualTo(owner.get("portConditionComparison"));
		assertThat(linked.get("motherOperationalState")).isEqualTo(owner.get("motherOperationalState"));
		assertThat(linked.get("aisFeed")).isEqualTo(owner.get("aisFeed"));

		JsonNode importer = read(importerToken, id);
		assertThat(importer.get("portConditionComparison")).isEqualTo(owner.get("portConditionComparison"));
		assertThat(importer.get("risk")).isEqualTo(owner.get("risk"));
		assertThat(count("shipment_risk_alert_deliveries")).isEqualTo(deliveries);

		jdbc.update("UPDATE shipments SET status = 'ARCHIVED' WHERE id = ?", id);
		entityManager.clear();
		JsonNode archived = read(forwarderToken, id);
		assertThat(archived.get("status").asText()).isEqualTo("ARCHIVED");
		assertThat(archived.get("portConditionComparison")).isEqualTo(owner.get("portConditionComparison"));
		assertThat(archived.get("recommendation")).isEqualTo(owner.get("recommendation"));

		mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.portConditionComparison").doesNotExist())
				.andExpect(jsonPath("$.motherOperationalState").doesNotExist())
				.andExpect(jsonPath("$.recommendation").doesNotExist())
				.andExpect(jsonPath("$.risk").doesNotExist());

		JsonNode listed = json.readTree(mvc.perform(get("/api/shipments")
				.param("reference", owner.get("shipmentReference").asText())
				.param("status", "ARCHIVED")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertThat(listed).hasSize(1);
		assertThat(listed.get(0).has("portConditionComparison")).isFalse();
		assertThat(listed.get(0).has("recommendation")).isFalse();
		assertThat(storedComparisonColumns()).isZero();
	}

	private JsonNode shipment(String port, BigDecimal latitude, BigDecimal longitude, BigDecimal speed, int status,
			Instant latestAt, BigDecimal earlierSpeed, Instant earlierAt) throws Exception {
		return placed(port, latitude, longitude, speed, status, latestAt, earlierSpeed, earlierAt);
	}

	private JsonNode placed(String port, BigDecimal latitude, BigDecimal longitude, BigDecimal speed, int status,
			Instant latestAt, BigDecimal earlierSpeed, Instant earlierAt) throws Exception {
		String name = vesselName();
		String mmsi = mmsi();
		if (earlierSpeed != null) {
			observe(mmsi, name, latitude, longitude, earlierSpeed, status, true, earlierAt, AisPositionSource.SEEDED);
		}
		observe(mmsi, name, latitude, longitude, speed, status, true, latestAt, AisPositionSource.AIS_STREAM);
		return read(forwarderToken, create(port, "MV " + name));
	}

	private void assertCompleted(JsonNode body, String outcome, String message, String knownPort) {
		JsonNode comparison = body.get("portConditionComparison");
		assertThat(comparison.get("outcome").asText()).isEqualTo(outcome);
		assertThat(comparison.get("reason").isNull()).isTrue();
		assertThat(comparison.get("message").asText()).isEqualTo(message);
		assertThat(comparison.get("vessel").asText()).isEqualTo("MOTHER");
		assertThat(comparison.get("knownPort").asText()).isEqualTo(knownPort);
		assertThat(comparison.get("earlierReportCount").asInt()).isEqualTo(1);
		assertThat(comparison.get("slowestOwnSpeedKnots").isNull()).isFalse();
		assertThat(body.get("motherOperationalState").get("description").asText()).endsWith(" near " + knownPort);
		assertThat(body.get("motherOperationalState").get("unreliable").asBoolean()).isFalse();
		assertSameReport(body);
	}

	private void assertUnavailable(JsonNode body, String reason, String message) {
		JsonNode comparison = body.get("portConditionComparison");
		assertThat(comparison.get("outcome").isNull()).isTrue();
		assertThat(comparison.get("reason").asText()).isEqualTo(reason);
		assertThat(comparison.get("message").asText()).isEqualTo(message);
		assertThat(comparison.get("vessel").asText()).isEqualTo("MOTHER");
		assertThat(comparison.get("earlierReportCount").asInt()).isZero();
		assertThat(comparison.get("slowestOwnSpeedKnots").isNull()).isTrue();
		assertThat(comparison.get("message").asText())
				.isNotEqualTo(body.get("motherOperationalState").get("description").asText());
		assertSameReport(body);
	}

	private void assertSameReport(JsonNode body) {
		JsonNode comparison = body.get("portConditionComparison");
		JsonNode state = body.get("motherOperationalState");
		JsonNode position = body.get("motherVesselPosition");
		if (comparison.get("ingestedAt").isNull()) {
			assertThat(state.get("ingestedAt").isNull()).isTrue();
		} else {
			assertThat(comparison.get("ingestedAt").asText()).isEqualTo(state.get("ingestedAt").asText());
		}
		if (position == null || position.isNull() || comparison.get("latitude").isNull()) {
			assertThat(position == null || position.isNull()).isEqualTo(comparison.get("latitude").isNull());
		} else {
			assertThat(comparison.get("latitude").decimalValue()).isEqualByComparingTo(position.get("latitude").decimalValue());
			assertThat(comparison.get("longitude").decimalValue()).isEqualByComparingTo(position.get("longitude").decimalValue());
			if (comparison.get("speedOverGroundKnots").isNull()) {
				assertThat(position.get("speedOverGroundKnots").isNull()).isTrue();
			} else {
				assertThat(comparison.get("speedOverGroundKnots").decimalValue())
						.isEqualByComparingTo(position.get("speedOverGroundKnots").decimalValue());
			}
			assertThat(comparison.get("ingestedAt").asText()).isEqualTo(position.get("ingestedAt").asText());
			assertThat(comparison.get("source").asText()).isEqualTo(position.get("source").asText());
		}
	}

	private long create(String port, String motherVessel) throws Exception {
		return create(port, motherVessel, "MV Feeder");
	}

	private long create(String port, String motherVessel, String feederVessel) throws Exception {
		String reference = "HBL-277-" + UUID.randomUUID().toString().substring(0, 8);
		Instant arrival = Instant.now();
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Jakarta",
						  "destination": "Rotterdam",
						  "transshipmentPort": "%s",
						  "motherVessel": "%s",
						  "plannedMotherArrivalAt": "%s",
						  "feederVessel": "%s",
						  "plannedFeederDepartureAt": "%s"
						}
						""".formatted(reference, port, motherVessel, arrival.atOffset(ZoneOffset.UTC), feederVessel,
						arrival.plus(Duration.ofHours(48)).atOffset(ZoneOffset.UTC))))
				.andExpect(status().isCreated())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("portConditionComparison");
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private JsonNode read(String token, long id) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private void observe(String mmsi, String name, BigDecimal latitude, BigDecimal longitude, BigDecimal speed,
			int status, boolean valid, Instant ingestedAt, AisPositionSource source) {
		latestPositions.record(new AisPosition(mmsi, name, latitude, longitude, speed, null, null, status, valid, null,
			ingestedAt.truncatedTo(ChronoUnit.MICROS), source));
	}

	private int storedComparisonColumns() {
		Integer columns = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND column_name ILIKE '%port_condition%'
				""", Integer.class);
		Integer tables = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name ILIKE '%port_condition%'
				""", Integer.class);
		return columns + tables;
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private String account(String role, Long companyId) {
		String email = "cdg277-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 277 User', ?, ?, id, ?
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
		String code = "CDG277" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-277 Other Company");
		return companyId(code);
	}

	private static String vesselName() {
		return "CDG277" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String mmsi() {
		return "5" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
	}
}
