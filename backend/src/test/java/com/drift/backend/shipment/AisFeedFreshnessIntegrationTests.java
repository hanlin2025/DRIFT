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

/** CDG-179. An authorised detail shows the feed and the report times. Another company does not. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AisFeedFreshnessIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired LatestAisPositions latestPositions;

	private String forwarderToken;
	private String outsiderToken;

	@BeforeEach
	void accounts() throws Exception {
		latestPositions.clear();
		jdbc.update("DELETE FROM vessel_observations WHERE source = 'AIS_STREAM'");
		forwarderToken = login(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		outsiderToken = login(account("FREIGHT_FORWARDER", companyId("STRAITS_FRESH_DEMO")));
	}

	@Test
	void anAuthorisedDetailShowsTheFeedAndReportTimesAndAnotherCompanyDoesNot() throws Exception {
		Instant stream = Instant.now().minus(Duration.ofMinutes(2));
		Instant seeded = stream.plus(Duration.ofSeconds(30));
		latestPositions.record(position("563179001", "PACIFIC HORIZON", 1, stream, AisPositionSource.AIS_STREAM));
		latestPositions.record(position("563179002", "STRAIT RUNNER", 5, seeded, AisPositionSource.SEEDED));
		long id = create(stream);

		MvcResult detail = mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andReturn();
		String body = detail.getResponse().getContentAsString();
		assertThat(JsonPath.read(body, "$.aisFeed.status").toString()).isEqualTo("LIVE");
		assertThat(Instant.parse(JsonPath.read(body, "$.aisFeed.lastReceivedAt").toString())).isEqualTo(stream);
		assertThat(Instant.parse(JsonPath.read(body, "$.arrivalComparison.lastReceivedAt").toString())).isEqualTo(stream);
		assertThat(Instant.parse(JsonPath.read(body, "$.motherOperationalState.ingestedAt").toString())).isEqualTo(stream);
		assertThat(Instant.parse(JsonPath.read(body, "$.feederOperationalState.ingestedAt").toString())).isEqualTo(seeded);
		assertThat(JsonPath.read(body, "$.feederVesselPosition.source").toString()).isEqualTo("SEEDED");
		assertThat(JsonPath.read(body, "$.remainingConnectionWindow.status").toString()).isEqualTo("AVAILABLE");

		mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.aisFeed").doesNotExist())
				.andExpect(jsonPath("$.arrivalComparison").doesNotExist())
				.andExpect(jsonPath("$.remainingConnectionWindow").doesNotExist());
	}

	private long create(Instant stream) throws Exception {
		String reference = "HBL-179-" + UUID.randomUUID().toString().substring(0, 8);
		String arrival = stream.minus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC).toString();
		String departure = stream.plus(Duration.ofHours(8)).atOffset(ZoneOffset.UTC).toString();
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "%s",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "%s"
						}
						""".formatted(reference, arrival, departure)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String account(String role, Long companyId) {
		String email = "cdg179-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 179 User', ?, ?, id, ?
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

	private static AisPosition position(String mmsi, String name, int navigationalStatus, Instant ingestedAt,
			AisPositionSource source) {
		return new AisPosition(mmsi, name, new BigDecimal("1.264"), new BigDecimal("103.840"),
				new BigDecimal("0.5"), null, null, navigationalStatus, true, null, ingestedAt, source);
	}
}
