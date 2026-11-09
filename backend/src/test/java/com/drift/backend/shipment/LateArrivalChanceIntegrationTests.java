package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CDG-209. Shipment detail returns the late-arrival chance from the same read as the arrival comparison.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(LateArrivalChanceClock.class)
class LateArrivalChanceIntegrationTests {

	static final Instant NOW = LateArrivalChanceClock.NOW;
	private static final String UNAVAILABLE = "Chance of arriving after feeder departure unavailable: ";
	private static final BigDecimal HALF_KNOT = new BigDecimal("0.5");

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
	void aHalfKnotEstimateReturnsTheChanceFromTheSameArrivalComparison() throws Exception {
		// Worked example row 7: 1 nautical mile at exactly 0.5 knots, so τ = 2h, a = 1h, r = +2h.
		String name = vesselName();
		observe(name, milesSouth(1), HALF_KNOT, NOW.minus(Duration.ofHours(1)));
		long id = create(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)), "MV " + name);
		int transitions = count("shipment_risk_transitions");
		int deliveries = count("shipment_risk_alert_deliveries");
		int states = count("shipment_risk_states");

		JsonNode first = read(forwarderToken, id);
		JsonNode chance = first.get("lateArrivalChance");
		assertThat(chance.get("available").asBoolean()).isTrue();
		assertThat(chance.get("probability").asDouble()).isCloseTo(0.1617, within(1e-3));
		assertThat(chance.get("display").asText()).isEqualTo("15%");
		assertThat(chance.get("label").asText())
				.isEqualTo("Chance of arriving after feeder departure: 15% · rough estimate");
		assertThat(chance.get("arrivalUsed")).isEqualTo(first.get("arrivalComparison").get("estimatedArrivalAt"));
		assertThat(chance.get("plannedFeederDepartureUsed")).isEqualTo(first.get("plannedFeederDepartureAt"));
		assertThat(chance.get("plannedWindowInvalid").asBoolean()).isFalse();
		assertThat(chance.get("unavailableReason").isNull()).isTrue();
		assertThat(first.get("arrivalComparison").get("speedOverGroundKnots").decimalValue()).isEqualByComparingTo(HALF_KNOT);

		assertThat(first.get("risk").get("level").asText()).isEqualTo("HIGH");
		assertThat(first.get("remainingConnectionWindow").get("status").asText()).isEqualTo("AVAILABLE");
		assertThat(first.get("remainingConnectionWindow").get("totalSeconds").asLong())
				.isCloseTo(Duration.ofHours(2).toSeconds(), within(1L));

		JsonNode second = read(forwarderToken, id);
		assertThat(second.get("lateArrivalChance")).isEqualTo(chance);
		assertThat(second.get("risk")).isEqualTo(first.get("risk"));
		assertThat(second.get("remainingConnectionWindow")).isEqualTo(first.get("remainingConnectionWindow"));
		assertThat(second.get("arrivalComparison")).isEqualTo(first.get("arrivalComparison"));
		assertThat(count("shipment_risk_transitions")).isEqualTo(transitions);
		assertThat(count("shipment_risk_alert_deliveries")).isEqualTo(deliveries);
		assertThat(count("shipment_risk_states")).isEqualTo(states);
		assertThat(storedChanceColumns()).isZero();
	}

	@Test
	void theLinkedImporterGetsTheSameChanceAndAnotherCompanyGetsNone() throws Exception {
		String name = vesselName();
		observe(name, milesSouth(1), HALF_KNOT, NOW.minus(Duration.ofHours(1)));
		long id = create(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)), "MV " + name);
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerCompanyId, id);
		entityManager.clear();

		JsonNode owner = read(forwarderToken, id);
		JsonNode importer = read(importerToken, id);
		assertThat(owner.get("lateArrivalChance").get("available").asBoolean()).isTrue();
		assertThat(importer.get("lateArrivalChance")).isEqualTo(owner.get("lateArrivalChance"));

		String denied = mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("You do not have access to this shipment"))
				.andExpect(jsonPath("$.lateArrivalChance").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		assertThat(denied).doesNotContain("probability").doesNotContain("Chance of arriving");
	}

	@Test
	void theListDoesNotCarryTheChance() throws Exception {
		String name = vesselName();
		observe(name, milesSouth(1), HALF_KNOT, NOW.minus(Duration.ofHours(1)));
		long id = create(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)), "MV " + name);
		String reference = read(forwarderToken, id).get("shipmentReference").asText();

		String listed = mvc.perform(get("/api/shipments").param("reference", reference)
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(listed).contains(reference).doesNotContain("lateArrivalChance");
	}

	@Test
	void aMissingFeederDepartureIsUnavailable() throws Exception {
		// The create API requires both planned times, so the departure is cleared in the database.
		String name = vesselName();
		observe(name, milesSouth(1), HALF_KNOT, NOW.minus(Duration.ofHours(1)));
		long id = create(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)), "MV " + name);
		allowIncompleteSchedule();
		jdbc.update("UPDATE shipments SET planned_feeder_departure_at = NULL WHERE id = ?", id);
		entityManager.clear();

		assertUnavailable(read(forwarderToken, id), "MISSING_FEEDER_DEPARTURE",
				"the planned feeder departure is not set.");
	}

	@Test
	void noPositionIsUnavailable() throws Exception {
		long id = create(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)), "MV " + vesselName());

		JsonNode body = read(forwarderToken, id);
		assertUnavailable(body, "NO_POSITION", "no position has been received for the mother vessel.");
		assertThat(body.get("arrivalComparison").get("unavailableReason").get("code").asText()).isEqualTo("NO_POSITION");
	}

	@Test
	void aPositionExactlySixHoursOldIsUsedAndOneSecondMoreIsOutdated() throws Exception {
		// 3 nautical miles at 0.5 knots, received 6h ago: τ = 6h, a = 6h, Â = now, r = +1h.
		String fresh = vesselName();
		observe(fresh, milesSouth(3), HALF_KNOT, NOW.minus(Duration.ofHours(6)));
		JsonNode used = read(forwarderToken,
				create(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), "MV " + fresh));
		assertThat(used.get("arrivalComparison").get("possiblyOutdated").asBoolean()).isFalse();
		assertThat(used.get("lateArrivalChance").get("available").asBoolean()).isTrue();
		assertThat(used.get("lateArrivalChance").get("probability").asDouble()).isCloseTo(0.3487, within(1e-3));
		assertThat(used.get("lateArrivalChance").get("display").asText()).isEqualTo("35%");

		String outdated = vesselName();
		observe(outdated, milesSouth(3), HALF_KNOT, NOW.minus(Duration.ofHours(6)).minusSeconds(1));
		JsonNode notUsed = read(forwarderToken,
				create(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), "MV " + outdated));
		assertThat(notUsed.get("arrivalComparison").get("possiblyOutdated").asBoolean()).isTrue();
		assertUnavailable(notUsed, "POSITION_OUTDATED", "the latest vessel position is more than 6 hours old.");
	}

	@Test
	void aFeederDepartureOneSecondAgoHasPassed() throws Exception {
		String name = vesselName();
		observe(name, milesSouth(1), HALF_KNOT, NOW.minus(Duration.ofHours(1)));
		JsonNode body = read(forwarderToken,
				create(NOW.minus(Duration.ofHours(1)), NOW.minusSeconds(1), "MV " + name));

		assertUnavailable(body, "FEEDER_DEPARTURE_PASSED",
				"the planned feeder departure has already passed, and DRIFT cannot confirm whether the connection was made.");
		assertThat(body.get("risk").get("level").asText()).isEqualTo("CRITICAL");
		assertThat(body.get("remainingConnectionWindow").get("status").asText()).isEqualTo("MISSED");
	}

	@Test
	void anInvalidPlannedWindowKeepsTheProbabilityAndSetsTheFlag() throws Exception {
		// The create API rejects a departure at or before the arrival, so the arrival is moved in the database.
		String name = vesselName();
		observe(name, milesSouth(1), HALF_KNOT, NOW.minus(Duration.ofHours(1)));
		long id = create(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)), "MV " + name);
		allowIncompleteSchedule();
		jdbc.update("UPDATE shipments SET planned_mother_arrival_at = planned_feeder_departure_at + INTERVAL '2 hours' WHERE id = ?",
				id);
		entityManager.clear();

		JsonNode chance = read(forwarderToken, id).get("lateArrivalChance");
		assertThat(chance.get("available").asBoolean()).isTrue();
		assertThat(chance.get("probability").asDouble()).isCloseTo(0.1617, within(1e-3));
		assertThat(chance.get("plannedWindowInvalid").asBoolean()).isTrue();
	}

	@Test
	void anArchivedShipmentTheViewerCanOpenKeepsTheChance() throws Exception {
		String name = vesselName();
		observe(name, milesSouth(1), HALF_KNOT, NOW.minus(Duration.ofHours(1)));
		long id = create(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)), "MV " + name);
		JsonNode active = read(forwarderToken, id);

		jdbc.update("UPDATE shipments SET status = 'ARCHIVED', importer_company_id = ? WHERE id = ?", importerCompanyId, id);
		entityManager.clear();

		JsonNode archived = read(forwarderToken, id);
		assertThat(archived.get("status").asText()).isEqualTo("ARCHIVED");
		assertThat(archived.get("lateArrivalChance")).isEqualTo(active.get("lateArrivalChance"));
		assertThat(read(importerToken, id).get("lateArrivalChance")).isEqualTo(active.get("lateArrivalChance"));
	}

	private static void assertUnavailable(JsonNode body, String code, String suffix) {
		JsonNode chance = body.get("lateArrivalChance");
		assertThat(chance.get("available").asBoolean()).isFalse();
		assertThat(chance.get("unavailableReason").get("code").asText()).isEqualTo(code);
		assertThat(chance.get("unavailableReason").get("message").asText()).isEqualTo(UNAVAILABLE + suffix);
		for (String field : new String[] { "probability", "display", "label", "arrivalUsed",
				"plannedFeederDepartureUsed", "plannedWindowInvalid" }) {
			assertThat(chance.get(field).isNull()).as(field).isTrue();
		}
	}

	private long create(Instant arrival, Instant departure, String motherVessel) throws Exception {
		String reference = "HBL-209-" + UUID.randomUUID().toString().substring(0, 8);
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
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private JsonNode read(String token, long id) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	/** A position due south of the port on its meridian, so the straight-line distance is the given nautical miles. */
	private BigDecimal milesSouth(int nauticalMiles) {
		return BigDecimal.valueOf(singapore.latitude())
				.subtract(BigDecimal.valueOf(nauticalMiles).divide(BigDecimal.valueOf(60), 12, RoundingMode.HALF_UP));
	}

	private void observe(String name, BigDecimal latitude, BigDecimal speed, Instant ingestedAt) {
		latestPositions.record(new AisPosition(mmsi(), name, latitude, BigDecimal.valueOf(singapore.longitude()), speed,
				null, null, 0, true, null, ingestedAt, AisPositionSource.SEEDED));
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

	private int storedChanceColumns() {
		Integer columns = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				WHERE table_schema = 'public'
					AND (column_name ILIKE '%chance%' OR column_name ILIKE '%probabilit%' OR column_name ILIKE '%late_arrival%')
				""", Integer.class);
		Integer tables = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'public'
					AND (table_name ILIKE '%chance%' OR table_name ILIKE '%probabilit%' OR table_name ILIKE '%late_arrival%')
				""", Integer.class);
		return columns + tables;
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private String account(String role, Long companyId) {
		String email = "cdg209-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 209 User', ?, ?, id, ?
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
		String code = "CDG209" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-209 Other Company");
		return companyId(code);
	}

	private static String vesselName() {
		return "CDG209" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String mmsi() {
		return "5" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
	}
}
