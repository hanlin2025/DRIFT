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

/** CDG-215. Authorised detail returns the recommendation from the same read as the connection risk. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(ShipmentRecommendationClock.class)
class ShipmentRecommendationIntegrationTests {

	static final Instant NOW = ShipmentRecommendationClock.NOW;
	private static final String NO_POSITION = "No AIS position has been received for the mother vessel.";

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
	void eachAssessedRiskStaysUnavailableUntilTheImpactIsReturned() throws Exception {
		assertAssessed(Duration.ofHours(12).minusSeconds(1), "HIGH",
				"Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
		assertAssessed(Duration.ofHours(12), "MEDIUM",
				"Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
		assertAssessed(Duration.ofHours(24), "LOW",
				"At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");

		JsonNode missed = detail(NOW, NOW.minus(Duration.ofHours(1)), NOW, new BigDecimal("12"));
		assertThat(missed.get("remainingConnectionWindow").get("missedBy").asText()).isEqualTo("0 seconds");
		assertRisk(missed, "CRITICAL",
				"The connection is expected to be missed. The mother vessel's estimated arrival is at the planned feeder departure.");
		assertNotReturned(missed);

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
		assertThat(invalid.get("remainingConnectionWindow").isNull()).isTrue();
		assertRisk(invalid, "CRITICAL",
				"The feeder vessel is planned to depart at or before the mother vessel's arrival, leaving no time to transfer cargo. The planned connection window is being used.");
		assertThat(invalid.get("risk").get("explanation").asText())
				.isNotEqualTo(missed.get("risk").get("explanation").asText());
		assertNotReturned(invalid);
		assertThat(invalid.get("arrivalComparison").get("unavailableReason").get("message").asText()).isEqualTo(NO_POSITION);
	}

	@Test
	void aShipmentWithNoRiskIsUnavailableAndIsNotNoAction() throws Exception {
		assertMissing(true, false);
		assertMissing(false, true);
		assertMissing(true, true);
	}

	@Test
	void anArchivedShipmentKeepsTheRecommendationAndAnotherCompanySeesNone() throws Exception {
		assertThat(recommendationColumns()).isZero();
		JsonNode owner = detail(NOW, NOW, NOW.plus(Duration.ofHours(24)), new BigDecimal("12"));
		long id = owner.get("id").asLong();
		String before = owner.get("recommendation").toString();
		int deliveries = count("shipment_risk_alert_deliveries");
		int transitions = count("shipment_risk_transitions");

		jdbc.update("UPDATE shipments SET status = 'ARCHIVED', importer_company_id = ? WHERE id = ?", importerCompanyId, id);
		entityManager.clear();

		JsonNode archived = read(forwarderToken, id);
		assertThat(archived.get("status").asText()).isEqualTo("ARCHIVED");
		assertThat(archived.get("recommendation").toString()).isEqualTo(before);
		assertThat(archived.get("risk")).isEqualTo(owner.get("risk"));
		assertThat(archived.get("riskContributions")).isEqualTo(owner.get("riskContributions"));
		assertThat(archived.get("remainingConnectionWindow")).isEqualTo(owner.get("remainingConnectionWindow"));
		assertThat(archived.get("arrivalComparison")).isEqualTo(owner.get("arrivalComparison"));
		assertThat(count("shipment_risk_alert_deliveries")).isEqualTo(deliveries);
		assertThat(count("shipment_risk_transitions")).isEqualTo(transitions);
		assertThat(recommendationColumns()).isZero();

		JsonNode importer = read(importerToken, id);
		assertThat(importer.get("recommendation")).isEqualTo(archived.get("recommendation"));
		assertThat(importer.get("risk")).isEqualTo(archived.get("risk"));
		assertThat(count("shipment_risk_alert_deliveries")).isEqualTo(deliveries);

		mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.recommendation").doesNotExist())
				.andExpect(jsonPath("$.risk").doesNotExist())
				.andExpect(jsonPath("$.riskContributions").doesNotExist());

		JsonNode listed = json.readTree(mvc.perform(get("/api/shipments")
				.param("reference", owner.get("shipmentReference").asText())
				.param("status", "ARCHIVED")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertThat(listed).hasSize(1);
		assertThat(listed.get(0).has("recommendation")).isFalse();
		assertThat(listed.get(0).has("risk")).isFalse();
	}

	private void assertAssessed(Duration remaining, String level, String explanation) throws Exception {
		JsonNode body = detail(NOW, NOW, NOW.plus(remaining), new BigDecimal("12"));
		assertThat(body.get("remainingConnectionWindow").get("status").asText()).isEqualTo("AVAILABLE");
		assertRisk(body, level, explanation);
		assertNotReturned(body);
		assertThat(body.get("riskContributions").get("assessed").asBoolean()).isTrue();
	}

	private void assertMissing(boolean mother, boolean feeder) throws Exception {
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
		JsonNode recommendation = body.get("recommendation");
		assertThat(recommendation.get("available").asBoolean()).isFalse();
		assertThat(recommendation.get("unavailable").asText()).isEqualTo(ShipmentRecommendation.BOTH_NOT_RETURNED);
		assertThat(recommendation.get("response").isNull()).isTrue();
		assertThat(recommendation.get("riskLevel").isNull()).isTrue();
		assertThat(recommendation.get("riskExplanation").isNull()).isTrue();
		assertThat(recommendation.get("impact").get("outcome").asText()).isEqualTo("UNAVAILABLE");
		assertThat(recommendation.get("impact").get("detail").asText()).isEqualTo(ShipmentRecommendationImpact.NOT_RETURNED);
		assertThat(recommendation.toString()).doesNotContain("NO_ACTION");
		assertThat(body.get("riskContributions").get("assessed").asBoolean()).isFalse();
		if (mother) {
			assertThat(body.get("arrivalComparison").isNull()).isTrue();
		} else {
			assertThat(body.get("arrivalComparison").get("unavailableReason").get("code").asText()).isEqualTo("NO_POSITION");
		}
	}

	private static void assertRisk(JsonNode body, String level, String explanation) {
		assertThat(body.get("risk").get("level").asText()).isEqualTo(level);
		assertThat(body.get("risk").get("explanation").asText()).isEqualTo(explanation);
	}

	private static void assertNotReturned(JsonNode body) {
		JsonNode recommendation = body.get("recommendation");
		assertThat(recommendation.get("available").asBoolean()).isFalse();
		assertThat(recommendation.get("unavailable").asText()).isEqualTo(ShipmentRecommendation.IMPACT_NOT_RETURNED);
		assertThat(recommendation.get("response").isNull()).isTrue();
		assertThat(recommendation.get("riskLevel").asText()).isEqualTo(body.get("risk").get("level").asText());
		assertThat(recommendation.get("riskExplanation").asText()).isEqualTo(body.get("risk").get("explanation").asText());
		assertThat(recommendation.get("impact").get("outcome").asText()).isEqualTo("UNAVAILABLE");
		assertThat(recommendation.get("impact").get("detail").asText()).isEqualTo(ShipmentRecommendationImpact.NOT_RETURNED);
		assertThat(recommendation.toString()).doesNotContain("NO_ACTION");
	}

	private JsonNode detail(Instant received, Instant arrival, Instant departure, BigDecimal speed) throws Exception {
		String name = vesselName();
		observe(name, singaporeLatitude(), longitude(), speed, received);
		return read(forwarderToken, create(arrival, departure, "MV " + name));
	}

	private long create(Instant arrival, Instant departure, String motherVessel) throws Exception {
		String reference = "HBL-215-" + UUID.randomUUID().toString().substring(0, 8);
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
		assertThat(body).doesNotContain("recommendation");
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

	private int recommendationColumns() {
		Integer columns = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND column_name ILIKE '%recommend%'
				""", Integer.class);
		Integer tables = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name ILIKE '%recommend%'
				""", Integer.class);
		return columns + tables;
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
		String email = "cdg215-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 215 User', ?, ?, id, ?
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
		String code = "CDG215" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-215 Other Company");
		return companyId(code);
	}

	private static String vesselName() {
		return "CDG215" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String mmsi() {
		return "5" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
	}
}
