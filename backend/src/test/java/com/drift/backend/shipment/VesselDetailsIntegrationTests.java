package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VesselDetailsIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired LatestAisPositions latestPositions;

	private String forwarderToken;
	private String importerToken;
	private String outsiderToken;
	private Long importerCompanyId;

	@BeforeEach
	void accounts() throws Exception {
		forwarderToken = login(account("FREIGHT_FORWARDER", companyId("HARBOURLINE_DEMO")));
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		importerToken = login(account("IMPORTER", importerCompanyId));
		String outsiderCode = "CDG145" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, 'CDG-145 Other', TRUE)", outsiderCode);
		outsiderToken = login(account("FREIGHT_FORWARDER", companyId(outsiderCode)));
		latestPositions.clear();
	}

	@Test
	void returnsAbsentIdentityOnTheDetailAndLeavesTheListAndCreateBodiesUnchanged() throws Exception {
		MvcResult created = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipment("HBL-145-PLAIN", "")))
				.andExpect(status().isCreated())
				.andReturn();
		assertThat(created.getResponse().getContentAsString()).doesNotContain("motherVesselImo");
		long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

		detail(forwarderToken, id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel").value("MV Pacific Horizon"))
				.andExpect(jsonPath("$.feederVessel").value("MV Strait Runner"))
				.andExpect(jsonPath("$.plannedMotherArrivalAt").exists())
				.andExpect(jsonPath("$.plannedFeederDepartureAt").exists())
				.andExpect(jsonPath("$.motherVesselImo").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.motherVesselMmsi").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.motherVesselOperator").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVesselImo").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVesselMmsi").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVesselOperator").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.motherVesselNameWarning").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVesselNameWarning").value(org.hamcrest.Matchers.nullValue()));

		mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].motherVessel").value("MV Pacific Horizon"))
				.andExpect(jsonPath("$[0].motherVesselImo").doesNotExist())
				.andExpect(jsonPath("$[0].motherVesselNameWarning").doesNotExist());
	}

	@Test
	void returnsStoredIdentityToTheManagingCompanyAndALinkedImporter() throws Exception {
		long id = create(shipment("HBL-145-IDENTITY", """
				, "motherVesselImo": "9387421",
				  "motherVesselMmsi": "563000111",
				  "motherVesselOperator": "Harbourline",
				  "feederVesselImo": "9411882",
				  "feederVesselMmsi": "565987654",
				  "feederVesselOperator": "Strait Feeders"
				"""));

		detail(forwarderToken, id)
				.andExpect(jsonPath("$.motherVesselImo").value("9387421"))
				.andExpect(jsonPath("$.motherVesselMmsi").value("563000111"))
				.andExpect(jsonPath("$.motherVesselOperator").value("Harbourline"))
				.andExpect(jsonPath("$.feederVesselImo").value("9411882"))
				.andExpect(jsonPath("$.feederVesselMmsi").value("565987654"))
				.andExpect(jsonPath("$.feederVesselOperator").value("Strait Feeders"))
				.andExpect(jsonPath("$.plannedMotherArrivalAt").value("2026-10-15T00:00:00Z"))
				.andExpect(jsonPath("$.plannedFeederDepartureAt").value("2026-10-16T04:00:00Z"));

		mvc.perform(patch("/api/shipments/" + id + "/link-importer")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerCompanyId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselImo").value("9387421"))
				.andExpect(jsonPath("$.feederVesselOperator").value("Strait Feeders"))
				.andExpect(jsonPath("$.importer.code").value("STRAITS_FRESH_DEMO"));

		detail(importerToken, id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVesselImo").value("9387421"))
				.andExpect(jsonPath("$.feederVesselMmsi").value("565987654"));
		detail(outsiderToken, id)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));
		detail(forwarderToken, 999999999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
	}

	@Test
	void warnsOnlyWhenTheLatestAisNameDiffersFromTheStoredName() throws Exception {
		latestPositions.record(position("563123456", "PACIFIC HORIZON"));
		latestPositions.record(position("565000222", "mv   strait   runner"));
		long id = create(shipment("HBL-145-WARN", """
				, "motherVesselMmsi": "563000111",
				  "feederVesselMmsi": "565987654"
				"""));

		detail(forwarderToken, id)
				.andExpect(jsonPath("$.motherVesselPosition.vesselName").value("PACIFIC HORIZON"))
				.andExpect(jsonPath("$.motherVesselPosition.mmsi").value("563123456"))
				.andExpect(jsonPath("$.motherVesselMmsi").value("563000111"))
				.andExpect(jsonPath("$.motherVesselNameWarning").value(VesselNames.MOTHER_MISMATCH))
				.andExpect(jsonPath("$.feederVesselPosition.vesselName").value("mv   strait   runner"))
				.andExpect(jsonPath("$.feederVesselMmsi").value("565987654"))
				.andExpect(jsonPath("$.feederVesselNameWarning").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void trackingWarningFollowsThePositionReturnedForThatRequest() throws Exception {
		latestPositions.record(position("563123456", "mv   pacific   horizon"));
		latestPositions.record(position("565000222", "STRAIT RUNNER"));
		long id = create(shipment("HBL-145-TRACK-WARN", ""));

		tracking(forwarderToken, id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel.vesselName").value("mv   pacific   horizon"))
				.andExpect(jsonPath("$.motherVesselNameWarning").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVessel.vesselName").value("STRAIT RUNNER"))
				.andExpect(jsonPath("$.feederVesselNameWarning").value(VesselNames.FEEDER_MISMATCH));

		latestPositions.record(positionAt("563123456", "PACIFIC HORIZON", "2026-10-05T04:00:00Z"));
		latestPositions.record(positionAt("565000222", "mv   strait   runner", "2026-10-05T04:00:00Z"));

		tracking(forwarderToken, id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel.vesselName").value("PACIFIC HORIZON"))
				.andExpect(jsonPath("$.motherVesselNameWarning").value(VesselNames.MOTHER_MISMATCH))
				.andExpect(jsonPath("$.feederVessel.vesselName").value("mv   strait   runner"))
				.andExpect(jsonPath("$.feederVesselNameWarning").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void staysQuietWhenTheAisNameMatchesOrThereIsNoNamedPosition() throws Exception {
		latestPositions.record(position("563123456", "mv   pacific   horizon"));
		long matched = create(shipment("HBL-145-MATCH", ""));
		detail(forwarderToken, matched)
				.andExpect(jsonPath("$.motherVesselPosition.mmsi").value("563123456"))
				.andExpect(jsonPath("$.motherVesselNameWarning").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVesselPosition").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.feederVesselNameWarning").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.motherVesselMmsi").value(org.hamcrest.Matchers.nullValue()));

		jdbc.update("DELETE FROM vessel_observations");
		latestPositions.clear();
		latestPositions.record(position("563999999", "   "));
		long blank = create(shipment("HBL-145-BLANK", ""));
		detail(forwarderToken, blank)
				.andExpect(jsonPath("$.motherVesselPosition").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.motherVesselNameWarning").value(org.hamcrest.Matchers.nullValue()));
	}

	private org.springframework.test.web.servlet.ResultActions detail(String token, long id) throws Exception {
		return mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions tracking(String token, long id) throws Exception {
		return mvc.perform(get("/api/shipments/" + id + "/tracking").header("Authorization", "Bearer " + token));
	}

	private long create(String body) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private static String shipment(String reference, String identity) {
		return """
				{
				  "shipmentReference": "%s",
				  "origin": "Shanghai, CN",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"%s
				}
				""".formatted(reference, identity);
	}

	private String account(String role, Long companyId) {
		String email = "cdg145-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 145 User', ?, ?, id, ?
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

	private static AisPosition position(String mmsi, String name) {
		return positionAt(mmsi, name, "2026-10-05T03:00:00Z");
	}

	private static AisPosition positionAt(String mmsi, String name, String ingestedAt) {
		return new AisPosition(mmsi, name, new BigDecimal("1.264"), new BigDecimal("103.82"),
				new BigDecimal("12.4"), new BigDecimal("175.0"), 176, 0, true, 42,
				Instant.parse(ingestedAt), AisPositionSource.AIS_STREAM);
	}
}
