package com.drift.backend.shipment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentTrackHistoryIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;
	@Autowired LatestAisPositions positions;

	private String forwarderToken;
	private String motherName;
	private String feederName;
	private String motherMmsi;
	private Instant now;

	@BeforeEach
	void setUp() throws Exception {
		positions.clear();
		forwarderToken = tokenFor(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		motherName = "CDG32 MOTHER " + suffix;
		feederName = "CDG32 FEEDER " + suffix;
		motherMmsi = mmsi();
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	@Test
	void returnsTheLast24HoursOfValidObservationsInTimeOrder() throws Exception {
		Long shipmentId = create("HBL-CDG32-ORDER");
		record(motherMmsi, motherName, ago(30), "9.0", true);
		record(motherMmsi, motherName, ago(3), "2.1", true);
		record(motherMmsi, motherName, ago(20), "11.5", true);
		record(motherMmsi, motherName, ago(10), "8.0", false);
		record(motherMmsi, motherName, ago(1), "12.4", null);

		history(forwarderToken, shipmentId, "")
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.vessel").value("MOTHER"))
				.andExpect(jsonPath("$.vesselName").value("MV " + motherName))
				.andExpect(jsonPath("$.mmsi").value(motherMmsi))
				.andExpect(jsonPath("$.windowHours").value(24))
				.andExpect(jsonPath("$.slowdownThresholdKnots").value(3))
				.andExpect(jsonPath("$.pointLimit").value(500))
				.andExpect(jsonPath("$.truncated").value(false))
				.andExpect(jsonPath("$.points[*].speedOverGroundKnots").value(contains(11.5, 2.1, 12.4)))
				.andExpect(jsonPath("$.points[0].id").isNumber())
				.andExpect(jsonPath("$.points[0].trueHeadingDegrees").value(176))
				.andExpect(jsonPath("$.points[0].courseOverGroundDegrees").value(175.0))
				.andExpect(jsonPath("$.points[0].ingestedAt").exists());
	}

	@Test
	void keepsTwoReportsWithTheSameTimestampAsDistinctPoints() throws Exception {
		Long shipmentId = create("HBL-CDG32-TIE");
		Instant tied = ago(2);
		record(motherMmsi, motherName, tied, "1.5", true);
		record(motherMmsi, motherName, tied, "6.2", true);

		MvcResult result = history(forwarderToken, shipmentId, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.points.length()").value(2))
				.andExpect(jsonPath("$.points[*].speedOverGroundKnots").value(contains(1.5, 6.2)))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		long first = ((Number) JsonPath.read(body, "$.points[0].id")).longValue();
		long second = ((Number) JsonPath.read(body, "$.points[1].id")).longValue();
		org.assertj.core.api.Assertions.assertThat(first).isLessThan(second);
		org.assertj.core.api.Assertions.assertThat(JsonPath.<String>read(body, "$.points[0].ingestedAt"))
				.isEqualTo(JsonPath.<String>read(body, "$.points[1].ingestedAt"));
	}

	@Test
	void switchesBetweenThePresetWindows() throws Exception {
		Long shipmentId = create("HBL-CDG32-WINDOWS");
		record(motherMmsi, motherName, ago(70), "4.0", true);
		record(motherMmsi, motherName, ago(12), "5.0", true);
		record(motherMmsi, motherName, ago(2), "6.0", true);

		history(forwarderToken, shipmentId, "?hours=6")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.windowHours").value(6))
				.andExpect(jsonPath("$.points[*].speedOverGroundKnots").value(contains(6.0)));
		history(forwarderToken, shipmentId, "?hours=72")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.points[*].speedOverGroundKnots").value(contains(4.0, 5.0, 6.0)));
	}

	@Test
	void keepsTheNewestPointsWhenTheWindowHoldsMoreThanTheLimit() throws Exception {
		Long shipmentId = create("HBL-CDG32-LIMIT");
		List<Object[]> rows = new ArrayList<>();
		for (int i = VesselTrackService.MAX_POINTS + 9; i >= 1; i--) {
			rows.add(new Object[] { motherMmsi, motherName, Timestamp.from(now.minus(Duration.ofMinutes(i))) });
		}
		jdbc.batchUpdate("""
				INSERT INTO vessel_observations (mmsi, vessel_name, latitude, longitude, speed_over_ground_knots,
					position_valid, ingested_at, source)
				VALUES (?, ?, 1.264, 103.82, 10, TRUE, ?, 'SEEDED')
				""", rows);

		history(forwarderToken, shipmentId, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.truncated").value(true))
				.andExpect(jsonPath("$.points.length()").value(VesselTrackService.MAX_POINTS))
				.andExpect(jsonPath("$.points[499].ingestedAt").value(org.hamcrest.Matchers.startsWith(
						now.minus(Duration.ofMinutes(1)).toString().substring(0, 16))));
	}

	@Test
	void readsTheFeederVesselAndReportsNoPointsWithoutAnyReport() throws Exception {
		Long shipmentId = create("HBL-CDG32-FEEDER");
		String feederMmsi = mmsi();
		record(feederMmsi, feederName, ago(5), "0.4", true);
		record(feederMmsi, feederName, ago(4), "0.2", true);

		history(forwarderToken, shipmentId, "?vessel=FEEDER")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vessel").value("FEEDER"))
				.andExpect(jsonPath("$.mmsi").value(feederMmsi))
				.andExpect(jsonPath("$.points.length()").value(2));
		history(forwarderToken, shipmentId, "?vessel=MOTHER")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mmsi").value(nullValue()))
				.andExpect(jsonPath("$.points.length()").value(0));
	}

	@Test
	void rejectsAnUnknownVesselOrWindow() throws Exception {
		Long shipmentId = create("HBL-CDG32-INVALID");

		history(forwarderToken, shipmentId, "?hours=5")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.hours").value(VesselTrackService.INVALID_WINDOW));
		history(forwarderToken, shipmentId, "?hours=abc")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.hours").value(VesselTrackService.INVALID_WINDOW));
		history(forwarderToken, shipmentId, "?vessel=TUG")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.vessel").value(VesselTrackService.INVALID_VESSEL));
	}

	@Test
	void onlyAuthorisedCompaniesSeeTheTrack() throws Exception {
		Long shipmentId = create("HBL-CDG32-ACCESS");
		record(motherMmsi, motherName, ago(2), "6.0", true);
		Long importerId = companyId("STRAITS_FRESH_DEMO");
		String importerToken = tokenFor(account("IMPORTER", importerId));
		String otherToken = tokenFor(account("FREIGHT_FORWARDER", createCompany()));

		history(importerToken, shipmentId, "").andExpect(status().isNotFound());
		entityManager.clear();
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerId, shipmentId);
		entityManager.clear();
		history(importerToken, shipmentId, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.points.length()").value(1));
		history(otherToken, shipmentId, "")
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"message\":\"" + ShipmentNotFoundException.MESSAGE + "\"}", true));
		mvc.perform(get("/api/shipments/" + shipmentId + "/track-history")).andExpect(status().isUnauthorized());
	}

	private Instant ago(int hours) {
		return now.minus(Duration.ofHours(hours));
	}

	private void record(String mmsi, String name, Instant at, String speed, Boolean valid) {
		positions.record(new AisPosition(mmsi, name, new BigDecimal("1.264"), new BigDecimal("103.82"),
				new BigDecimal(speed), new BigDecimal("175.0"), 176, 0, valid, 12, at, AisPositionSource.SEEDED));
	}

	private static String mmsi() {
		return String.valueOf(ThreadLocalRandom.current().nextLong(200_000_000L, 999_999_999L));
	}

	private ResultActions history(String token, Long shipmentId, String query) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId + "/track-history" + query)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private Long create(String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference":"%s",
						  "origin":"Shanghai, CN",
						  "destination":"Jakarta, ID",
						  "transshipmentPort":"Singapore",
						  "motherVessel":"MV %s",
						  "plannedMotherArrivalAt":"2026-10-10T09:30:00+08:00",
						  "feederVessel":"MV %s",
						  "plannedFeederDepartureAt":"2026-10-10T15:45:00+08:00"
						}
						""".formatted(reference, motherName, feederName)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
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
		String email = "cdg32-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-32 User', ?, ?, id, ? FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany() {
		String code = "CDG32" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-32 Other Company");
		return companyId(code);
	}
}
