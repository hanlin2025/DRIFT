package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentObservationReportIntegrationTests {

	private static final Instant RECEIVED = Instant.parse("2026-10-05T03:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired LatestAisPositions latestPositions;

	private String forwarderToken;
	private Long importerCompanyId;

	@BeforeEach
	void accounts() throws Exception {
		forwarderToken = login(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		latestPositions.clear();
	}

	@Test
	void attachesTheStoredReportTimeAndSourceAndDoesNotInventAPosition() throws Exception {
		latestPositions.record(position("563177001", "PACIFIC HORIZON", 1, RECEIVED, AisPositionSource.AIS_STREAM));
		latestPositions.record(position("563177002", "STRAIT RUNNER", 5, RECEIVED.plusSeconds(60),
				AisPositionSource.SEEDED));
		OffsetDateTime motherStored = storedAt("563177001");
		long id = create();

		mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselPosition.source").value("AIS_STREAM"))
				.andExpect(jsonPath("$.motherVesselPosition.ingestedAt").value("2026-10-05T03:00:00Z"))
				.andExpect(jsonPath("$.feederVesselPosition.source").value("SEEDED"))
				.andExpect(jsonPath("$.feederVesselPosition.ingestedAt").value("2026-10-05T03:01:00Z"))
				.andExpect(jsonPath("$.arrivalComparison.lastReceivedAt").value("2026-10-05T03:00:00Z"))
				.andExpect(jsonPath("$.motherOperationalState.label").value("At anchor"))
				.andExpect(jsonPath("$.motherOperationalState.description").value("At anchor near Singapore"))
				.andExpect(jsonPath("$.motherOperationalState.unreliable").value(false))
				.andExpect(jsonPath("$.motherOperationalState.ingestedAt").value("2026-10-05T03:00:00Z"))
				.andExpect(jsonPath("$.feederOperationalState.label").value("Moored"))
				.andExpect(jsonPath("$.feederOperationalState.ingestedAt").value("2026-10-05T03:01:00Z"));

		mvc.perform(get("/api/shipments/" + id + "/tracking").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel.source").value("AIS_STREAM"))
				.andExpect(jsonPath("$.feederVessel.source").value("SEEDED"));

		assertThat(storedAt("563177001").toInstant()).isEqualTo(motherStored.toInstant());

		mvc.perform(patch("/api/shipments/" + id + "/link-importer")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherOperationalState.ingestedAt").value("2026-10-05T03:00:00Z"))
				.andExpect(jsonPath("$.motherVesselPosition.source").value("AIS_STREAM"))
				.andExpect(jsonPath("$.arrivalComparison.lastReceivedAt").value("2026-10-05T03:00:00Z"));
	}

	@Test
	void aMissingObservationHasNoReportTimeAndNoInventedPosition() throws Exception {
		long id = create();

		mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselPosition").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVesselPosition").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.motherOperationalState.label").value("Unknown"))
				.andExpect(jsonPath("$.motherOperationalState.description").value("Unknown"))
				.andExpect(jsonPath("$.motherOperationalState.unreliable").value(false))
				.andExpect(jsonPath("$.motherOperationalState.ingestedAt").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederOperationalState.ingestedAt").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.arrivalComparison.unavailableReason.code").value("NO_POSITION"))
				.andExpect(jsonPath("$.arrivalComparison.lastReceivedAt").value(org.hamcrest.Matchers.nullValue()));

		mvc.perform(get("/api/shipments/" + id + "/tracking").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVessel").value(org.hamcrest.Matchers.nullValue()));
	}

	private OffsetDateTime storedAt(String mmsi) {
		return jdbc.queryForObject("SELECT ingested_at FROM vessel_observations WHERE mmsi = ?", OffsetDateTime.class,
				mmsi);
	}

	private long create() throws Exception {
		String reference = "HBL-177-" + UUID.randomUUID().toString().substring(0, 8);
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
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						""".formatted(reference)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String account(String role, Long companyId) {
		String email = "cdg177-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 177 User', ?, ?, id, ?
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
