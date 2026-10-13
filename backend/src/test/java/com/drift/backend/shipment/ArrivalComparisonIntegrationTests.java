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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG150_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG150_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG150_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG150_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG150_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG150_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG150_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG150_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ArrivalComparisonDatabaseGuard.class)
@org.springframework.transaction.annotation.Transactional
class ArrivalComparisonIntegrationTests {

	private static final String DENIED = """
			{"message":"%s"}""".formatted(ShipmentDetailForbiddenException.MESSAGE).trim();
	private static final BigDecimal LATEST_LATITUDE = new BigDecimal("1.261234567");
	private static final BigDecimal LATEST_LONGITUDE = new BigDecimal("103.821234567");
	private static final BigDecimal LATEST_SPEED = new BigDecimal("12.375");
	private static final BigDecimal SLOW_SPEED = new BigDecimal("0.499");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired ObjectMapper json;

	private final ArrivalEstimateCalculator calculator = new ArrivalEstimateCalculator(PortCatalog.load());
	private Instant ingestedAt;
	private OffsetDateTime plannedArrival;
	private OffsetDateTime feederDeparture;
	private Long forwarderCompanyId;
	private String forwarderToken;
	private String importerToken;
	private Long importerCompanyId;
	private Long availableShipmentId;
	private Long slowShipmentId;
	private Long missingShipmentId;

	@BeforeEach
	void setUp() throws Exception {
		ingestedAt = Instant.now().minus(Duration.ofHours(7)).truncatedTo(ChronoUnit.MICROS);
		if (ingestedAt.getNano() == 0) {
			ingestedAt = ingestedAt.plusNanos(123_456_000);
		}
		plannedArrival = ingestedAt.atOffset(ZoneOffset.ofHours(8));
		feederDeparture = plannedArrival.plusHours(6);

		forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarderToken = tokenFor(account("FREIGHT_FORWARDER", forwarderCompanyId));
		importerToken = tokenFor(account("IMPORTER", importerCompanyId));

		String mmsi = mmsi();
		observe(mmsi, "Mother", new BigDecimal("8.500000001"), new BigDecimal("100.250000001"), new BigDecimal("0.5"),
				ingestedAt.minus(Duration.ofHours(2)));
		observe(mmsi, "Mother", LATEST_LATITUDE, LATEST_LONGITUDE, LATEST_SPEED, ingestedAt);
		observe(mmsi(), "Slow", LATEST_LATITUDE, LATEST_LONGITUDE, SLOW_SPEED, ingestedAt);

		availableShipmentId = create(forwarderToken, "HBL-CDG150-AVAILABLE-" + UUID.randomUUID(), "MV Mother");
		slowShipmentId = create(forwarderToken, "HBL-CDG150-SLOW-" + UUID.randomUUID(), "MV Slow");
		missingShipmentId = create(forwarderToken, "HBL-CDG150-NONE-" + UUID.randomUUID(), "MV Absent");
	}

	@Test
	void authorizedDetailReturnsTheRetainedObservationAndLeavesTheScheduleUnchanged() throws Exception {
		JsonNode body = read(forwarderToken, availableShipmentId);

		assertThat(body.get("motherVesselPosition").get("vesselName").asText()).isEqualTo("Mother");
		assertSameObservation(body.get("motherVesselPosition"), LATEST_LATITUDE, LATEST_LONGITUDE, LATEST_SPEED);
		assertThat(body.get("feederVesselPosition").isNull()).isTrue();
		assertAvailableComparison(body, originalFix(LATEST_SPEED));
		assertScheduleUnchanged(body);
		assertRetainedRecord(body, VesselNames.MOTHER_MISMATCH);
	}

	@Test
	void linkedImporterReceivesTheSameComparison() throws Exception {
		MvcResult linked = mvc.perform(patch("/api/shipments/" + availableShipmentId + "/link-importer")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode linkBody = json.readTree(linked.getResponse().getContentAsString());
		JsonNode importerBody = read(importerToken, availableShipmentId);

		assertAvailableComparison(linkBody, originalFix(LATEST_SPEED));
		assertAvailableComparison(importerBody, originalFix(LATEST_SPEED));
		assertScheduleUnchanged(importerBody);
		assertRetainedRecord(linkBody, VesselNames.MOTHER_MISMATCH);
		assertRetainedRecord(importerBody, VesselNames.MOTHER_MISMATCH);
		assertThat(importerBody.get("arrivalComparison")).isEqualTo(linkBody.get("arrivalComparison"));
	}

	@Test
	void unavailableReasonsKeepEveryArrivalFieldNull() throws Exception {
		JsonNode missing = read(forwarderToken, missingShipmentId);
		JsonNode slow = read(forwarderToken, slowShipmentId);

		assertThat(missing.get("motherVesselPosition").isNull()).isTrue();
		assertUnavailable(missing, null);
		assertRetainedRecord(missing, null);
		assertSameObservation(slow.get("motherVesselPosition"), LATEST_LATITUDE, LATEST_LONGITUDE, SLOW_SPEED);
		assertUnavailable(slow, new ArrivalFix(LATEST_LATITUDE, LATEST_LONGITUDE, SLOW_SPEED, ingestedAt));
		assertRetainedRecord(slow, VesselNames.MOTHER_MISMATCH);
		assertScheduleUnchanged(missing);
		assertScheduleUnchanged(slow);
	}

	@Test
	void anotherCompanyIsDeniedWithoutTheComparison() throws Exception {
		String otherToken = tokenFor(account("IMPORTER", createCompany()));

		mvc.perform(get("/api/shipments/" + availableShipmentId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
				.andExpect(status().isForbidden())
				.andExpect(content().json(DENIED, true));
	}

	@Test
	void listAndTrackingOmitTheComparison() throws Exception {
		JsonNode listed = json.readTree(mvc.perform(get("/api/shipments")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		JsonNode tracking = json.readTree(mvc.perform(get("/api/shipments/" + availableShipmentId + "/tracking")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());

		assertThat(listed).isNotEmpty();
		listed.forEach(item -> assertThat(item.has("arrivalComparison")).isFalse());
		assertThat(tracking.has("arrivalComparison")).isFalse();
		assertThat(tracking.get("motherVessel").get("ingestedAt").asText())
				.isEqualTo(read(forwarderToken, availableShipmentId).get("motherVesselPosition").get("ingestedAt").asText());
		assertThat(tracking.get("motherVesselNameWarning").asText()).isEqualTo(VesselNames.MOTHER_MISMATCH);
		assertThat(tracking.has("feederVesselNameWarning")).isTrue();
		assertThat(tracking.get("feederVesselNameWarning").isNull()).isTrue();
	}

	private void assertAvailableComparison(JsonNode body, ArrivalFix fix) throws Exception {
		ArrivalEstimate expected = calculator.estimate(fix, "Singapore", plannedArrival, Clock.systemUTC());
		JsonNode comparison = body.get("arrivalComparison");
		assertThat(comparison.toString()).isEqualTo(json.valueToTree(expected).toString());
		assertThat(comparison.get("unavailableReason").isNull()).isTrue();
		assertThat(comparison.has("unavailableReason")).isTrue();
		assertThat(comparison.get("estimatedArrivalAt").isTextual()).isTrue();
		assertThat(comparison.get("lastReceivedAt").asText()).isEqualTo(body.get("motherVesselPosition").get("ingestedAt").asText());
		assertThat(comparison.get("speedOverGroundKnots").isNumber()).isTrue();
		assertThat(comparison.get("differenceSeconds").isNumber()).isTrue();
		assertThat(comparison.get("differenceNanos").isNumber()).isTrue();
		assertThat(new BigDecimal(comparison.get("speedOverGroundKnots").asText())).isEqualByComparingTo(fix.speedOverGroundKnots());
		assertThat(comparison.get("limitation").asText()).isEqualTo(ArrivalEstimate.LIMITATION);
		assertThat(comparison.get("possiblyOutdated").asBoolean()).isTrue();
		assertThat(comparison.has("plannedMotherArrivalAt")).isFalse();
	}

	private void assertUnavailable(JsonNode body, ArrivalFix fix) throws Exception {
		ArrivalEstimate expected = calculator.estimate(fix, "Singapore", plannedArrival, Clock.systemUTC());
		JsonNode comparison = body.get("arrivalComparison");
		assertThat(comparison.toString()).isEqualTo(json.valueToTree(expected).toString());
		assertExplicitNull(comparison, "estimatedArrivalAt");
		assertExplicitNull(comparison, "limitation");
		assertExplicitNull(comparison, "speedOverGroundKnots");
		assertExplicitNull(comparison, "classification");
		assertExplicitNull(comparison, "difference");
		assertExplicitNull(comparison, "differenceSeconds");
		assertExplicitNull(comparison, "differenceNanos");
		assertExplicitNull(comparison, "lastReceivedAt");
		assertExplicitNull(comparison, "possiblyOutdated");
		assertThat(comparison.get("unavailableReason").get("code").asText()).isEqualTo(expected.unavailableReason().code());
		assertThat(comparison.get("unavailableReason").get("message").asText()).isEqualTo(expected.unavailableReason().message());
		assertThat(comparison.get("estimatedArrivalAt").isNull()).isTrue();
	}

	private static void assertExplicitNull(JsonNode comparison, String field) {
		assertThat(comparison.has(field)).isTrue();
		assertThat(comparison.get(field).isNull()).isTrue();
	}

	private void assertSameObservation(JsonNode position, BigDecimal latitude, BigDecimal longitude, BigDecimal speed) throws Exception {
		assertThat(new BigDecimal(position.get("latitude").asText())).isEqualByComparingTo(latitude);
		assertThat(new BigDecimal(position.get("longitude").asText())).isEqualByComparingTo(longitude);
		assertThat(new BigDecimal(position.get("speedOverGroundKnots").asText())).isEqualByComparingTo(speed);
		assertThat(position.get("ingestedAt")).isEqualTo(json.valueToTree(ingestedAt));
	}

	private void assertRetainedRecord(JsonNode body, String motherWarning) {
		assertThat(body.get("companyId").asLong()).isEqualTo(forwarderCompanyId);
		assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
		assertThat(OffsetDateTime.parse(body.get("plannedMotherArrivalAt").asText()).toInstant())
				.isEqualTo(plannedArrival.toInstant());
		for (String field : new String[] {"motherVesselImo", "motherVesselMmsi", "motherVesselOperator",
				"feederVesselImo", "feederVesselMmsi", "feederVesselOperator"}) {
			assertThat(body.has(field)).isTrue();
			assertThat(body.get(field).isNull()).isTrue();
		}
		assertNamedWarning(body, "motherVesselNameWarning", motherWarning);
		assertNamedWarning(body, "feederVesselNameWarning", null);
	}

	private static void assertNamedWarning(JsonNode body, String field, String expected) {
		assertThat(body.has(field)).isTrue();
		if (expected == null) {
			assertThat(body.get(field).isNull()).isTrue();
		} else {
			assertThat(body.get(field).asText()).isEqualTo(expected);
		}
	}

	private static void assertScheduleUnchanged(JsonNode body) {
		assertThat(body.get("connectionWindow").get("duration").asText()).isEqualTo("6 hours");
		assertThat(body.get("connectionWindow").get("totalSeconds").asLong()).isEqualTo(21_600L);
		assertThat(body.get("risk").get("level").asText()).isEqualTo("HIGH");
		assertThat(body.get("risk").get("explanation").asText())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels.");
	}

	private ArrivalFix originalFix(BigDecimal speed) {
		return new ArrivalFix(LATEST_LATITUDE, LATEST_LONGITUDE, speed, ingestedAt);
	}

	private JsonNode read(String token, Long shipmentId) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + shipmentId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private Long create(String token, String reference, String motherVessel) throws Exception {
		String payload = """
				{
				  "shipmentReference":"%s",
				  "origin":"Jakarta",
				  "destination":"Rotterdam",
				  "transshipmentPort":"Singapore",
				  "motherVessel":"%s",
				  "plannedMotherArrivalAt":"%s",
				  "feederVessel":"MV Feeder",
				  "plannedFeederDepartureAt":"%s"
				}
				""".formatted(reference, motherVessel, plannedArrival, feederDeparture);
		MvcResult result = mvc.perform(post("/api/shipments")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(status().isCreated())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("arrivalComparison");
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private void observe(String mmsi, String vesselName, BigDecimal latitude, BigDecimal longitude, BigDecimal speed,
			Instant ingested) {
		jdbc.update("""
				INSERT INTO vessel_observations (
					mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'SEEDED')
				""", mmsi, vesselName, latitude, longitude, speed, ingested.toString());
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
		String email = "cdg150-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-150 User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany() {
		String code = "CDG150" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-150 Other Company");
		return companyId(code);
	}

	private static String mmsi() {
		return "5" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}
}
